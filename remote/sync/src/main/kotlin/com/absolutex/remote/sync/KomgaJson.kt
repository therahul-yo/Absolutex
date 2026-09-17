package com.absolutex.remote.sync

import java.util.Base64
import org.json.JSONObject

// Absurd-quantity guard: a lying server must not page the client forever.
internal const val MAX_PAGES = 500

internal fun basicCredentials(username: String, password: CharArray): String {
    val joined = "$username:${password.concatToString()}".toByteArray(Charsets.UTF_8)
    return "Basic " + Base64.getEncoder().encodeToString(joined)
}

internal fun <T> accumulatePages(load: (Int) -> KomgaPage<T>): List<T> {
    val out = mutableListOf<T>()
    var page = 0
    // Empty content also ends the walk: some servers repeat last=false past the end.
    while (page < MAX_PAGES) {
        val batch = load(page)
        out.addAll(batch.items)
        if (batch.last || batch.items.isEmpty()) break
        page += 1
    }
    return out
}

internal fun parseSeriesPage(body: String): KomgaPage<SeriesRef> = parseJson(body) { root ->
    KomgaPage(
        items = reqObjects(root, "content").map(::parseSeriesRef),
        last = req(root, "last", root::getBoolean),
    )
}

internal fun parseSeriesRef(item: JSONObject): SeriesRef = SeriesRef(
    id = req(item, "id", item::getString),
    name = req(item, "name", item::getString),
)

internal fun parseBooksPage(body: String): KomgaPage<BookRef> = parseJson(body) { root ->
    KomgaPage(
        items = reqObjects(root, "content").map(::parseBookRef),
        last = req(root, "last", root::getBoolean),
    )
}

internal fun parseBookRef(item: JSONObject): BookRef {
    val media = req(item, "media", item::getJSONObject)
    return BookRef(
        id = req(item, "id", item::getString),
        name = req(item, "name", item::getString),
        seriesId = req(item, "seriesId", item::getString),
        pageCount = req(media, "pagesCount", media::getInt),
    )
}

internal fun parseBookWithProgress(body: String): KomgaBook = parseJson(body) { root ->
    KomgaBook(book = parseBookRef(root), progress = embeddedProgress(root))
}

internal fun embeddedProgress(root: JSONObject): RemoteProgress? {
    if (!root.has("readProgress") || root.isNull("readProgress")) return null
    val progress = root.getJSONObject("readProgress")
    return RemoteProgress(
        page = req(progress, "page", progress::getInt),
        completed = req(progress, "completed", progress::getBoolean),
        updatedAt = parseInstant(req(progress, "lastModified", progress::getString)),
    )
}

internal fun parsePages(body: String): List<PageRef> = parseJsonArray(body) { array ->
    List(array.length(), array::getJSONObject).map {
        PageRef(
            number = req(it, "number", it::getInt),
            fileName = req(it, "fileName", it::getString),
            mediaType = req(it, "mediaType", it::getString),
        )
    }
}
