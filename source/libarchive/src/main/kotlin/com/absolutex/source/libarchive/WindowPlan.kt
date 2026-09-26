package com.absolutex.source.libarchive

/**
 * How a page-read request will actually be served, decided before any archive is touched.
 *
 * Split out from [LibArchiveSource.openPages] so the decision is testable without a file
 * descriptor. That split is the point: the decision is where the bugs live — a run that is
 * silently not a run, a bounds check that happens after the read rather than before it, a
 * short result quietly accepted — and none of the three was reachable in a unit test while
 * the JNI call sat in the same method.
 */
internal sealed interface WindowPlan {
    /** [count] consecutive archive entries from [fromOrdinal], in one header walk. */
    data class Run(val fromOrdinal: Int, val count: Int) : WindowPlan

    /** Not consecutive: each page is read the way random access is read. */
    data object Individually : WindowPlan
}

/**
 * The cheapest correct plan for [indexes].
 *
 * A run of consecutive pages costs one header walk; the same pages read one at a time cost
 * one walk each — on the 300-page 6 MP corpus, 61.2 ms against 45.8 ms for a 10-page window
 * (tools/bench-decode.sh). So a genuinely consecutive run becomes [WindowPlan.Run] and
 * everything else becomes [WindowPlan.Individually], which is what the caller would have done
 * anyway.
 *
 * Consecutiveness is judged on archive ORDINALS, not on page indexes. A book's page order is
 * a sorted view of its entries, so pages 5 and 6 are frequently not adjacent entries when
 * junk entries or a sidecar sit between them. Judging on page indexes would hand libarchive a
 * run that does not exist, and the error would be a silently wrong page rather than a loud
 * one — the same failure the by-ordinal design exists to prevent.
 *
 * @throws IndexOutOfBoundsException if any index is out of range, before any read. A window
 *   applied to a valid prefix and abandoned at an invalid index is worse than no window: the
 *   caller cannot tell how much of what it received is trustworthy.
 * @throws IllegalArgumentException if [indexes] is empty. There is no cheapest plan for "no
 *   pages" and a caller asking for none is a bug worth naming.
 */
internal fun planWindow(indexes: List<Int>, ordinals: IntArray): WindowPlan {
    indexes.forEach { index ->
        if (index !in ordinals.indices) {
            throw IndexOutOfBoundsException("page $index of ${ordinals.size}")
        }
    }
    if (indexes.isEmpty()) throw IllegalArgumentException("no pages requested")
    val from = ordinals[indexes.first()]
    val consecutive = indexes.withIndex().all { (i, page) -> ordinals[page] == from + i }
    return if (consecutive) WindowPlan.Run(fromOrdinal = from, count = indexes.size)
    else WindowPlan.Individually
}

/**
 * Fails when a window returned a different number of entries than were asked for.
 *
 * Short is the dangerous direction: it means pages are missing, and a caller indexing the
 * result would read a shifted array and show page N+1 where it meant page N. Long means the
 * ordinal contract in nativeExtractWindow has drifted from nativeExtract's, which is equally
 * worth failing on rather than truncating quietly.
 *
 * @throws IllegalStateException on any mismatch. Deliberately not IOException: nothing was
 *   read wrongly, the bridge simply did not honour the contract, and that is a defect in the
 *   bridge rather than bad input from the book.
 */
internal fun requireWindowSize(actual: Int, requested: Int) {
    if (actual != requested) {
        throw IllegalStateException("window returned $actual entries for $requested pages")
    }
}
