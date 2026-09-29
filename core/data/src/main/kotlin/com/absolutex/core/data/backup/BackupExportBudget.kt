package com.absolutex.core.data.backup

/** Worst-case JSON escaping is six bytes per character; reserves include pretty-printing overhead. */
internal class BackupExportBudget {
    private var remaining = MAX_BACKUP_BYTES - HEADER_RESERVE
    private val books = mutableSetOf<String>()

    fun include(identity: String): Boolean {
        val cost = identity.length * ESCAPED_CHAR_BYTES + ROW_RESERVE
        if (cost > remaining || (identity !in books && books.size >= MAX_BOOKS)) return false
        remaining -= cost
        books += identity
        return true
    }

    private companion object {
        const val HEADER_RESERVE = 16_384
        const val ESCAPED_CHAR_BYTES = 6
        const val ROW_RESERVE = 512
    }
}
