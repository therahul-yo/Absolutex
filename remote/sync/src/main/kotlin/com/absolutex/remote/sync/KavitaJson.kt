package com.absolutex.remote.sync

internal fun parseKavitaSession(body: String): KavitaSession = parseJson(body) { root ->
    KavitaSession(
        token = req(root, "token", root::getString),
        refreshToken = req(root, "refreshToken", root::getString),
    )
}

internal fun parseKavitaLibraries(body: String): List<LibraryRef> = parseJsonArray(body) { array ->
    List(array.length(), array::getJSONObject).map {
        LibraryRef(id = req(it, "id", it::getInt), name = req(it, "name", it::getString))
    }
}

internal fun parseKavitaSeries(body: String): List<SeriesRef> = parseJsonArray(body) { array ->
    // Kavita ids are ints; the shared SeriesRef carries strings (Komga uses string ids).
    List(array.length(), array::getJSONObject).map {
        SeriesRef(id = req(it, "id", it::getInt).toString(), name = req(it, "name", it::getString))
    }
}

internal fun parseKavitaProgress(body: String): RemoteProgress = parseJson(body) { root ->
    RemoteProgress(
        page = req(root, "pageNum", root::getInt),
        // ProgressDto carries no completed flag; completion is inferred from series pagesRead
        // during live validation (TODO(experimental)).
        completed = false,
        updatedAt = optInstant(root, "lastModifiedUtc"),
    )
}

/**
 * One chapter's files for identity matching. File names come from full server paths
 * (frequently Windows-style), so matching uses the base name on either separator.
 */
data class KavitaChapterFiles(
    val chapterId: Int,
    val volumeId: Int,
    val seriesId: Int,
    val files: List<KavitaFileRef>,
)

data class KavitaFileRef(val fileName: String, val bytes: Long, val pages: Int?)

internal fun parseKavitaVolumeIds(body: String): List<Int> = parseJsonArray(body) { array ->
    List(array.length(), array::getJSONObject).map { req(it, "id", it::getInt) }
}

internal fun parseKavitaVolume(body: String): List<KavitaChapterFiles> = parseJson(body) { root ->
    val volumeId = req(root, "id", root::getInt)
    val seriesId = req(root, "seriesId", root::getInt)
    optObjects(root, "chapters").map { chapter ->
        val chapterId = req(chapter, "id", chapter::getInt)
        KavitaChapterFiles(
            chapterId = chapterId,
            volumeId = optInt(chapter, "volumeId") ?: volumeId,
            seriesId = seriesId,
            files = optObjects(chapter, "files").mapNotNull { file ->
                val path = optString(file, "filePath") ?: return@mapNotNull null
                val bytes = optLong(file, "bytes") ?: return@mapNotNull null
                KavitaFileRef(
                    fileName = path.substringAfterLast('/').substringAfterLast('\\'),
                    bytes = bytes,
                    pages = optInt(file, "pages"),
                )
            },
        )
    }
}

internal fun parseKavitaSeriesLibraryId(body: String): Int = parseJson(body) { root ->
    req(root, "libraryId", root::getInt)
}
