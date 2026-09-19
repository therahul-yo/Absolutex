package com.absolutex.feature.library

import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.data.ChangeResult
import java.io.File

class FakeRepository(private val events: MutableList<LibraryChange> = mutableListOf()) : LibraryRepositoryStub() {
    override suspend fun applyChange(change: LibraryChange, locationRoot: File?): ChangeResult {
        events.add(change)
        return ChangeResult.Ignored
    }
}

abstract class LibraryRepositoryStub : com.absolutex.core.data.LibraryRepository(
    dao = FakeDao(),
    scanner = FakeScanner(),
    now = { 0L }
) {
    override suspend fun observeLibrary(): kotlinx.coroutines.flow.Flow<List<com.absolutex.core.data.LibraryBook>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun search(query: String): List<com.absolutex.core.data.LibraryBook> = emptyList()
    override suspend fun scanLocation(root: File): com.absolutex.core.data.ScanResult = com.absolutex.core.data.ScanResult(0, 0)
    override suspend fun scanTree(root: com.absolutex.core.scan.TreeEntry, tree: com.absolutex.core.scan.DocumentTree, includeHidden: Boolean): com.absolutex.core.data.ScanResult = com.absolutex.core.data.ScanResult(0, 0)
    override suspend fun setRead(paths: Set<String>, read: Boolean): com.absolutex.feature.library.LibraryNotice = com.absolutex.feature.library.LibraryNotice.BatchUnsupported(com.absolutex.feature.library.UnsupportedReason.DELETE_NOT_IMPLEMENTED)
    override suspend fun setFavorite(paths: Set<String>, favorite: Boolean): com.absolutex.feature.library.LibraryNotice = com.absolutex.feature.library.LibraryNotice.BatchUnsupported(com.absolutex.feature.library.UnsupportedReason.FAVOURITES_STORE_MISSING)
    override suspend fun delete(paths: Set<String>): com.absolutex.feature.library.LibraryNotice = com.absolutex.feature.library.LibraryNotice.BatchUnsupported(com.absolutex.feature.library.UnsupportedReason.DELETE_NOT_IMPLEMENTED)
    abstract override suspend fun applyChange(change: LibraryChange, locationRoot: File?): ChangeResult
}

class FakeDao : com.absolutex.core.data.LibraryDao() {
    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<com.absolutex.core.data.LibraryBook>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun allOnce(): List<com.absolutex.core.data.LibraryBook> = emptyList()
    override suspend fun search(query: String, encodedQuery: String): List<com.absolutex.core.data.LibraryBook> = emptyList()
    override suspend fun upsertPreservingAddedAt(batch: List<com.absolutex.core.data.LibraryBook>) {}
    override suspend fun deleteStaleIn(rootPath: String, scanId: Long): Int = 0
    override suspend fun deletePath(path: String) {}
}

class FakeScanner : com.absolutex.core.scan.LibraryScanner() {
    override fun scan(roots: List<File>): kotlinx.coroutines.flow.Flow<com.absolutex.core.scan.ScannedBook> = kotlinx.coroutines.flow.flowOf()
}
