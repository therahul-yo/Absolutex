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
