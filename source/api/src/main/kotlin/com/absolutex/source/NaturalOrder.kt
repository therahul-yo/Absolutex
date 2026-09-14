package com.absolutex.source

/**
 * Natural, case-insensitive ordering: "page2" before "page10".
 *
 * Compares digit-runs numerically and text-runs lexicographically. Digit runs are compared by
 * value first and only then by length, so "01" and "1" tie on value and the shorter (fewer
 * leading zeros) sorts first — this keeps mixed zero-padding in a scan ("1.jpg", "01.jpg")
 * stable instead of interleaving.
 *
 * Path separators sort before any other character so a subfolder's pages stay contiguous
 * rather than interleaving with a sibling file that shares its prefix.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]

            if (ca.isDigit() && cb.isDigit()) {
                val ea = endOfDigits(a, i)
                val eb = endOfDigits(b, j)
                val cmp = compareDigitRuns(a, i, ea, b, j, eb)
                if (cmp != 0) return cmp
                i = ea
                j = eb
                continue
            }

            val ra = rank(ca)
            val rb = rank(cb)
            if (ra != rb) return ra - rb
            if (ra == 0) {  // both separators, equal
                i++; j++; continue
            }

            val la = ca.lowercaseChar()
            val lb = cb.lowercaseChar()
            if (la != lb) return la.compareTo(lb)
            i++
            j++
        }
        // Shared prefix: the shorter string sorts first.
        return (a.length - i) - (b.length - j)
    }

    private fun rank(c: Char): Int = if (c == '/' || c == '\\') 0 else 1

    private fun endOfDigits(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }

    /** Compares two digit runs by numeric value without parsing (runs can exceed Long range). */
    private fun compareDigitRuns(a: String, as_: Int, ae: Int, b: String, bs: Int, be: Int): Int {
        var ia = as_
        var ib = bs
        while (ia < ae && a[ia] == '0') ia++   // strip leading zeros
        while (ib < be && b[ib] == '0') ib++
        val lenA = ae - ia
        val lenB = be - ib
        if (lenA != lenB) return lenA - lenB   // more significant digits == larger number
        var k = 0
        while (k < lenA) {
            val d = a[ia + k].compareTo(b[ib + k])
            if (d != 0) return d
            k++
        }
        // Equal value: fewer leading zeros first, so padding variants stay deterministic.
        return (ae - as_) - (be - bs)
    }
}
