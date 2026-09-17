package com.absolutex.remote.smb

import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * CharArray to UTF-8 bytes and back without ever materialising a [String]. The credential
 * store's contract uses [CharArray] precisely so callers can zero the secret after use; routing
 * through `concatToString`/`String(bytes)` pins an unscrubbable copy on the heap until GC.
 * Every buffer allocated here is zeroed before return except the result itself, which the
 * caller owns and must zero when done.
 */
internal object CharCodec {

    /** Exact UTF-8 bytes of [chars]; the encoder's oversized scratch buffer is zeroed. */
    fun CharArray.toUtf8Bytes(): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) {
            encoded.array().fill(0)
        }
        return bytes
    }

    /** Decodes UTF-8 [bytes]; the decoder's scratch buffer is zeroed. */
    fun ByteArray.toChars(): CharArray {
        val decoded = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(this))
        val chars = CharArray(decoded.remaining())
        decoded.get(chars)
        if (decoded.hasArray()) {
            decoded.array().fill(Char.MIN_VALUE)
        }
        return chars
    }
}
