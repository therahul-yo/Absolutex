package com.absolutex.core.data.backup

import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.PageView
import com.absolutex.core.data.ReadingProgress
import com.absolutex.model.PageLayout
import com.absolutex.model.ReadingFlow
import java.io.InputStream
import java.math.BigDecimal

internal object BackupCodec {
    fun read(input: InputStream): BackupData {
        val root = BackupJson.read(input)
        val version = root.number("schemaVersion")
        if (version > SCHEMA_VERSION) throw FutureBackupVersion()
        require(version == SCHEMA_VERSION.toLong())
        val data = BackupData(
            appVersion = root.text("appVersion"),
            progress = root.entries("progress").map { row ->
                val count = row.index("pageCount")
                val index = row.index("pageIndex")
                require(count > 0 && index < count)
                ReadingProgress(row.identity("bookId"), index, count, row.number("updatedAt"))
            },
            history = root.entries("history").map { row ->
                PageView(bookKey = row.identity("bookId"), page = row.index("pageIndex"),
                    atEpochMs = row.number("atEpochMs"))
            },
            bookmarks = root.entries("bookmarks").map { row ->
                Bookmark(row.identity("bookId"), row.index("pageIndex"), row.number("createdAt"))
            },
            bookPrefs = root.entries("bookPrefs").map { row ->
                BookPrefs(row.identity("bookId"), row.optionalEnum("readingFlow", ReadingFlow.entries),
                    row.optionalEnum("pageLayout", PageLayout.entries))
            },
            favourites = root.list("favourites", MAX_BOOKS).map { value ->
                mapOf("bookId" to value).identity("bookId")
            }.toSet(),
            preferences = BackupPreferences.validate(if ("preferences" in root) root["preferences"].objectValue() else emptyMap()),
        )
        validate(data)
        return data
    }

    fun validate(data: BackupData) {
        require(data.bookIds.size <= MAX_BOOKS)
        require(data.progress.size <= MAX_BOOKS && data.bookPrefs.size <= MAX_BOOKS)
        require(data.history.size <= MAX_ENTRIES && data.bookmarks.size <= MAX_ENTRIES)
        require(data.favourites.size <= MAX_BOOKS)
        require(data.progress.map { it.bookId }.distinct().size == data.progress.size)
        require(data.bookPrefs.map { it.bookId }.distinct().size == data.bookPrefs.size)
    }

    private fun Map<String, Any?>.entries(key: String): List<Map<String, Any?>> =
        list(key, MAX_ENTRIES).map { it.objectValue() }

    private fun Map<String, Any?>.list(key: String, limit: Int): List<Any?> {
        if (key !in this) return emptyList()
        return (get(key) as? List<*>)?.also { require(it.size <= limit) } ?: error("Expected array")
    }

    private fun Map<String, Any?>.number(key: String): Long =
        ((get(key) as? BigDecimal)?.longValueExact() ?: error("Expected integer"))
            .also { require(it >= 0) }

    private fun Map<String, Any?>.index(key: String): Int = number(key).also { require(it <= Int.MAX_VALUE) }.toInt()

    private fun Map<String, Any?>.text(key: String): String =
        (get(key) as? String ?: error("Expected string")).also { require(it.length <= MAX_TEXT) }

    private fun Map<String, Any?>.identity(key: String): String = text(key).also {
        // Opaque identity, never a path to open. Colons/slashes in legitimate display names stay opaque.
        require(it.isNotBlank() && it.none { char -> char.isISOControl() })
        require(it.substringAfterLast(':', "").toLongOrNull()?.let { size -> size >= 0 } == true)
    }

    private fun <T : Enum<T>> Map<String, Any?>.optionalEnum(key: String, values: List<T>): String? {
        if (get(key) == null) return null
        val name = text(key)
        return values.firstOrNull { it.name == name }?.name
    }
}
