package com.absolutex.source.epub

import java.net.URLDecoder

/**
 * Resolves the hrefs inside an EPUB to the ZIP entry names they name.
 *
 * Three things make this more than string concatenation, and each one is a real file in the wild:
 * an href is relative to the document that contains it (the package document's own folder, then
 * each spine document's folder), it is percent-encoded because it is a URL and not a path, and it
 * may walk upwards with `..` out of `OEBPS/text/` into `OEBPS/images/`.
 */
internal object EpubPath {

    /**
     * The entry [href] names, seen from a document at [baseDir], or null when it names nothing
     * inside the container.
     *
     * Null covers the cases an EPUB reader must not chase: an absolute URL (a remote image — this
     * app fetches nothing the user did not ask for), and a path that climbs above the container
     * root. The latter cannot be satisfied anyway, since every lookup is against the ZIP's own
     * entry list, but refusing it here means a traversal attempt never becomes a lookup at all.
     */
    fun resolve(baseDir: String, href: String): String? {
        val withoutFragment = href.substringBefore('#').trim()
        if (withoutFragment.isEmpty()) return null
        if (isRemote(withoutFragment)) return null
        val decoded = decode(withoutFragment)
        // A leading "/" means the container root, not the host filesystem root.
        val joined = if (decoded.startsWith('/')) decoded.drop(1) else join(baseDir, decoded)
        return normalise(joined)
    }

    /** The folder part of an entry name, "" for an entry at the container root. */
    fun parentOf(entryName: String): String = entryName.substringBeforeLast('/', "")

    /**
     * A scheme-bearing reference points outside the container.
     *
     * Matched on the scheme grammar rather than a list of protocols: any `foo:` prefix is a URL
     * this reader has no business resolving, and a Windows-style "C:\..." is equally not ours.
     */
    private fun isRemote(href: String): Boolean {
        if (href.startsWith("//")) return true                      // protocol-relative
        val colon = href.indexOf(':')
        if (colon <= 0) return false
        val scheme = href.substring(0, colon)
        return scheme.first().isLetter() && scheme.all { it.isLetterOrDigit() || it in "+-." }
    }

    private fun join(baseDir: String, href: String): String =
        if (baseDir.isEmpty()) href else "$baseDir/$href"

    /** Percent-decoding only; a literal "+" is a plus, not a space (this is a path, not a form). */
    private fun decode(href: String): String =
        runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)

    /**
     * Collapses "." and "..", and refuses anything that climbs past the root.
     *
     * Returning null rather than clamping at the root is deliberate: a clamped "../../x.jpg" would
     * silently resolve to "x.jpg" and could match a real entry the document never asked for.
     */
    private fun normalise(path: String): String? {
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit                                     // "a//b" and "a/./b" are "a/b"
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString("/")
    }
}
