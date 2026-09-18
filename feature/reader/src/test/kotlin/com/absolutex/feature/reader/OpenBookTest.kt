package com.absolutex.feature.reader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.ContentResolverTree
import com.absolutex.core.scan.LibraryScanner
import com.absolutex.core.scan.SafScanner
import com.absolutex.core.scan.TreeEntry
import com.absolutex.model.BookIdentity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private const val AUTHORITY = "com.absolutex.test.documents"

/**
 * PR #29: an image-folder book's `path` is the folder itself, which has no length of its own — so
 * the reader's identity for it must be computed the same way the scanner keyed it, on both the
 * filesystem and SAF routes, or the folder book's saved position never joins its library row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenBookTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `a container file's identity matches what the scanner keyed it by, unaffected by the folder fix`() {
        val file = tmp.newFile("Batman 001.cbz")
        file.writeBytes(ByteArray(12_345))

        val scanned = requireNotNull(LibraryScanner.scanFile(file))
        val scanSideKey = BookIdentity.of(scanned.displayName, scanned.sizeBytes)

        assertEquals(BookIdentity.of("Batman 001.cbz", 12_345), scanSideKey)
        assertEquals(scanSideKey, context.identityOf(Uri.fromFile(file)))
    }

    @Test fun `a filesystem folder book's identity matches what the scanner keyed it by`() {
        val dir = tmp.newFolder("Loose Pages")
        File(dir, "001.jpg").writeBytes(ByteArray(PAGE_1_BYTES))
        File(dir, "002.jpg").writeBytes(ByteArray(PAGE_2_BYTES))
        File(dir, "003.jpg").writeBytes(ByteArray(PAGE_3_BYTES))

        val scanned = requireNotNull(LibraryScanner.scanFile(dir))
        val scanSideKey = BookIdentity.of(scanned.displayName, scanned.sizeBytes)

        assertEquals(BookIdentity.of("Loose Pages", TOTAL_BYTES), scanSideKey)
        assertEquals(scanSideKey, context.identityOf(Uri.fromFile(dir)))
    }

    @Test fun `a SAF folder book's identity matches what the scanner keyed it by`() = runTest {
        val provider = Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(AUTHORITY).get()
        provider.addDoc("root", parentId = null, name = "Comics", mime = DocumentsContract.Document.MIME_TYPE_DIR)
        provider.addDoc("folder", "root", "Loose Pages", DocumentsContract.Document.MIME_TYPE_DIR)
        provider.addDoc("p1", "folder", "001.jpg", "image/jpeg", PAGE_1_BYTES.toLong())
        provider.addDoc("p2", "folder", "002.jpg", "image/jpeg", PAGE_2_BYTES.toLong())
        provider.addDoc("p3", "folder", "003.jpg", "image/jpeg", PAGE_3_BYTES.toLong())

        val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "folder")
        val root = TreeEntry(uri = treeUri.toString(), name = "Comics", isDirectory = true)

        // Scan-side and reader-side both go through the same ContentResolverTree a real scan and
        // a real open use — the fake is only the ContentProvider underneath it.
        val scanned = SafScanner.scan(root, ContentResolverTree(context)).toList().single()
        val scanSideKey = BookIdentity.of(scanned.displayName, scanned.sizeBytes)

        assertEquals(BookIdentity.of("Loose Pages", TOTAL_BYTES), scanSideKey)
        assertEquals(scanSideKey, context.identityOf(folderUri))
    }

    private companion object {
        const val PAGE_1_BYTES = 100
        const val PAGE_2_BYTES = 250
        const val PAGE_3_BYTES = 75
        const val TOTAL_BYTES = (PAGE_1_BYTES + PAGE_2_BYTES + PAGE_3_BYTES).toLong()
    }
}

/**
 * A minimal DocumentsProvider-shaped [ContentProvider]: just enough of the two queries SAF uses.
 *
 * Not `private`: Robolectric instantiates it by reflection, off its `Class` object, so it needs an
 * ordinary public constructor rather than whatever the JVM gives a file-private top-level class.
 */
class FakeDocumentsProvider : ContentProvider() {
    private data class Doc(val name: String, val mime: String, val size: Long?)

    private val docs = mutableMapOf<String, Doc>()
    private val childrenOf = mutableMapOf<String, MutableList<String>>()

    fun addDoc(id: String, parentId: String?, name: String, mime: String, size: Long? = null) {
        docs[id] = Doc(name, mime, size)
        if (parentId != null) childrenOf.getOrPut(parentId) { mutableListOf() }.add(id)
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val documentId = DocumentsContract.getDocumentId(uri)
        return if (uri.lastPathSegment == "children") childrenCursor(documentId) else documentCursor(documentId)
    }

    private fun childrenCursor(parentId: String) = MatrixCursor(CHILD_COLUMNS).apply {
        childrenOf[parentId].orEmpty().forEach { id ->
            val doc = docs.getValue(id)
            addRow(arrayOf<Any?>(id, doc.name, doc.mime, doc.size))
        }
    }

    private fun documentCursor(documentId: String) = MatrixCursor(DOC_COLUMNS).apply {
        val doc = docs.getValue(documentId)
        addRow(arrayOf<Any?>(doc.name, doc.size, doc.mime))
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    private companion object {
        val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val DOC_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}
