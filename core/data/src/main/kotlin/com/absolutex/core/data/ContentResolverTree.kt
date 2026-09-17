package com.absolutex.core.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.absolutex.core.scan.DocumentTree
import com.absolutex.core.scan.TreeEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * A [DocumentTree] over a SAF tree the user granted (§5.1).
 *
 * One query per directory, asking for exactly the four columns a scan uses. A document whose
 * columns cannot be read is skipped rather than failing the walk: a provider that dies mid-scan
 * should cost its own subtree, not the library.
 */
class ContentResolverTree @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentTree {

    override fun children(documentUri: String): List<TreeEntry> {
        val parent = Uri.parse(documentUri)
        // A tree's root Uri has no document id of its own; every Uri below it does.
        val documentId = runCatching { DocumentsContract.getDocumentId(parent) }
            .getOrElse { runCatching { DocumentsContract.getTreeDocumentId(parent) }.getOrNull() }
            ?: return emptyList()
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentId)
        }.getOrNull() ?: return emptyList()
        return runCatching {
            context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                // Resolved once per cursor, not per row: nothing here says a provider must return
                // the requested projection in the requested order, only that these columns exist.
                val columns = Columns(
                    id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    size = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE),
                )
                buildList {
                    while (cursor.moveToNext()) entryOf(cursor, parent, columns)?.let(::add)
                }
            }
        }.getOrNull().orEmpty()
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
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
