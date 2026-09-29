package com.absolutex.feature.library

/**
 * The types the "Open file" picker is offered, for `ActivityResultContracts.OpenDocument`.
 *
 * Providers disagree about what a comic archive is called: the same .cbr arrives as
 * `application/x-cbr`, `application/vnd.rar` or plain `application/octet-stream`, and a provider
 * that cannot tell says nothing useful at all. So the specific types come first, for the pickers
 * that honour them, and [ANY] last, so a file with an unknown type can always be chosen. The reader
 * sniffs the content rather than trusting a name, so a wrong pick ends in the normal error state.
 */
object OpenFileTypes {

    const val ANY = "*/*"

    val MIME_TYPES: Array<String> = arrayOf(
        // cbz / zip
        "application/vnd.comicbook+zip",
        "application/x-cbz",
        "application/zip",
        "application/x-zip-compressed",
        // cbr / rar
        "application/vnd.comicbook-rar",
        "application/x-cbr",
        "application/vnd.rar",
        "application/x-rar-compressed",
        // cb7 / 7z
        "application/x-cb7",
        "application/x-7z-compressed",
        // cbt / tar
        "application/x-cbt",
        "application/x-tar",
        // documents
        "application/pdf",
        "application/epub+zip",
        ANY,
    )
}
