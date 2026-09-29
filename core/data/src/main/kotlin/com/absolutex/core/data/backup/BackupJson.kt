package com.absolutex.core.data.backup

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict JSON with bounded bytes, nesting, strings and total values, including unknown fields. */
internal object BackupJson {
    private const val MAX_DEPTH = 16
    private const val MAX_NUMBER_TEXT = 64
    private const val MAX_NUMBER_SCALE = 128

    fun read(input: InputStream): Map<String, Any?> {
        val bytes = input.readNBytes(MAX_BACKUP_BYTES + 1)
        require(bytes.size <= MAX_BACKUP_BYTES)
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            .toString().removePrefix("\uFEFF")
        return JsonReader(StringReader(utf8)).use { reader ->
            reader.isLenient = false
            val value = readValue(reader, 0, intArrayOf(0)).objectValue()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            value
        }
    }

    private fun readValue(reader: JsonReader, depth: Int, count: IntArray): Any? {
        require(depth <= MAX_DEPTH && ++count[0] <= MAX_JSON_VALUES)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(reader, depth, count)
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val values = mutableListOf<Any?>()
                while (reader.hasNext()) values += readValue(reader, depth + 1, count)
                reader.endArray()
                values
            }
            JsonToken.STRING -> reader.nextString().also { require(it.length <= MAX_TEXT) }
            JsonToken.NUMBER -> {
                val number = reader.nextString()
                require(number.length <= MAX_NUMBER_TEXT)
                BigDecimal(number).also { require(it.scale() in -MAX_NUMBER_SCALE..MAX_NUMBER_SCALE) }
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); null }
            else -> error("Invalid JSON value")
        }
    }

    private fun readObject(reader: JsonReader, depth: Int, count: IntArray): Map<String, Any?> {
        reader.beginObject()
        val values = linkedMapOf<String, Any?>()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(key.length <= MAX_TEXT && key !in values)
            values[key] = readValue(reader, depth + 1, count)
        }
        reader.endObject()
        return values
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.objectValue(): Map<String, Any?> =
    this as? Map<String, Any?> ?: error("Expected object")
