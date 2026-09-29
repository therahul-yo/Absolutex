package com.absolutex.core.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.absolutex.core.scan.DocumentTree
import com.absolutex.core.scan.TreeEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject

/**
 * A [DocumentTree] over a SAF tree the user granted (§5.1).
 *
 * One query per directory, asking for exactly the four columns a scan uses. A document whose
 * columns cannot be read is skipped rather than failing the walk: a provider that dies mid-scan
 * should cost its own subtree, not the library. A folder that cannot be read is reported through
 * [reporting], so it is never mistaken for an empty one.
 */
class ContentResolverTree @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentTree {

    override fun children(documentUri: String): List<TreeEntry> = read(documentUri, failures = null)

    /**
     * The same tree, counting into [failures] every folder it could not read. A separate view
     * rather than a mutable field, so two scans running at once each get their own verdict.
     */
    fun reporting(failures: ReadFailures): DocumentTree = DocumentTree { read(it, failures) }

    /**
     * A folder's children, or empty when it could not be read: the scan carries on past it, so a
     * dead provider costs its own subtree. It is no longer silent — the exception CLASS is logged
     * (never the Uri or a file name, which are the user's) and [failures] is told, so the caller
     * can say "couldn't read this folder" instead of presenting it as an empty one.
     */
    private fun read(documentUri: String, failures: ReadFailures?): List<TreeEntry> {
        val rows = runCatching { query(Uri.parse(documentUri)) }
            .onFailure { Log.w(TAG, "folder read failed: ${it.javaClass.name}") }
            .getOrNull()
        if (rows == null) failures?.record()
        return rows.orEmpty()
    }

    /** The rows of one folder. Throws when the folder cannot be queried at all. */
    private fun query(parent: Uri): List<TreeEntry> {
        // A tree's root Uri has no document id of its own; every Uri below it does.
        val documentId = runCatching { DocumentsContract.getDocumentId(parent) }
            .getOrElse { runCatching { DocumentsContract.getTreeDocumentId(parent) }.getOrNull() }
            ?: throw IllegalArgumentException("no document id")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentId)
        val cursor = context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw IOException("provider returned no cursor")
        return cursor.use { c ->
            // Resolved once per cursor, not per row: nothing here says a provider must return
            // the requested projection in the requested order, only that these columns exist.
            val columns = Columns(
                id = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                name = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                mime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE),
                size = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE),
            )
            buildList {
                while (c.moveToNext()) entryOf(c, parent, columns)?.let(::add)
            }
        }
    }

    /** Null for a document with no id or name: it can be neither opened nor sorted, so it is not a book. */
    private fun entryOf(cursor: Cursor, parent: Uri, columns: Columns): TreeEntry? {
        val id = cursor.getString(columns.id) ?: return null
        val name = cursor.getString(columns.name) ?: return null
        return TreeEntry(
            uri = DocumentsContract.buildDocumentUriUsingTree(parent, id).toString(),
            name = name,
            isDirectory = cursor.getString(columns.mime) == DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = if (cursor.isNull(columns.size)) 0 else cursor.getLong(columns.size),
        )
    }

    /** This cursor's column positions, looked up by name once rather than assumed from the projection. */
    private data class Columns(val id: Int, val name: Int, val mime: Int, val size: Int)

    private companion object {
        const val TAG = "ContentResolverTree"

        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
