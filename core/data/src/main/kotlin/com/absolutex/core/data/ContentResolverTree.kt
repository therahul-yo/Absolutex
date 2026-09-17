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
                buildList {
                    while (cursor.moveToNext()) entryOf(cursor, parent)?.let(::add)
                }
            }
        }.getOrNull().orEmpty()
    }

    /** Null for a document with no id or name: it can be neither opened nor sorted, so it is not a book. */
    private fun entryOf(cursor: Cursor, parent: Uri): TreeEntry? {
        val id = cursor.getString(COLUMN_ID) ?: return null
        val name = cursor.getString(COLUMN_NAME) ?: return null
        return TreeEntry(
            uri = DocumentsContract.buildDocumentUriUsingTree(parent, id).toString(),
            name = name,
            isDirectory = cursor.getString(COLUMN_MIME) == DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = if (cursor.isNull(COLUMN_SIZE)) 0 else cursor.getLong(COLUMN_SIZE),
        )
    }

    private companion object {
        // Column indices are this projection's order: a cursor has no names to look up by.
        const val COLUMN_ID = 0
        const val COLUMN_NAME = 1
        const val COLUMN_MIME = 2
        const val COLUMN_SIZE = 3

        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
