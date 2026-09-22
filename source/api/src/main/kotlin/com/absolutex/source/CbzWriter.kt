package com.absolutex.source

import java.io.OutputStream
import java.nio.file.attribute.FileTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pages are numbered from one in the archive; index 0 is written as "001". */
private const val FIRST_PAGE_ORDINAL = 1

/**
 * Comics are conventionally numbered to at least three digits, and a reader showing entry names
 * looks wrong below that. Wider books widen past it — see [cbzEntryName].
 */
internal const val MIN_ORDINAL_DIGITS = 3

/**
 * Every entry is stamped with the same instant, so that exporting one book twice on one device
 * produces byte-identical archives — which is what makes "has this export changed?" answerable
 * without reading the whole file.
 *
 * That guarantee stops at the device, and deliberately so: a ZIP records its entry times as a DOS
 * timestamp in **local** time, which `java.util.zip` derives from the default time zone with no
 * public way to override it. The same instant written in Kolkata and in Los Angeles genuinely
 * differs in the bytes (measured: 05:30 against the previous day's 16:00). Cross-device
 * reproducibility is therefore not on offer here, and this comment exists so nobody later reads
 * "fixed" as "identical everywhere".
 *
 * 2020-01-01T00:00:00Z rather than the conventional 1980-01-01: the DOS format cannot represent
 * anything before 1980, and 1980-01-01T00:00Z is already 1979 in every zone behind UTC, so that
 * "safe" epoch is the one date guaranteed to underflow for half the world.
 */
private val FIXED_ENTRY_TIME: FileTime = FileTime.fromMillis(1_577_836_800_000L)

/**
 * The name page [index] is written under, of [pageCount] pages, keeping [originalName]'s extension.
 *
 * Zero-padded so that **lexicographic order is reading order**. Absolutex sorts entries with
 * [NaturalOrder] and would read "2.jpg" before "10.jpg" either way, but an exported CBZ is opened
 * by whatever the user owns, and plenty of readers sort plainly. Padding is the difference between
 * an export that reads correctly everywhere and one that only reads correctly here.
 *
 * The width grows with the book rather than being fixed at three: a 1,000-page book padded to
 * three digits puts "1000" after "999" lexicographically only by accident of equal length, and a
 * 10,000-page one would genuinely misorder.
 *
 * The extension is carried over untouched because the bytes are too — an exported page is the
 * source's own bytes, never re-encoded, so it must keep the extension that describes them (and
 * that [EntryFilter.isPage] accepts).
 */
fun cbzEntryName(index: Int, pageCount: Int, originalName: String): String {
    val digits = maxOf(MIN_ORDINAL_DIGITS, pageCount.toString().length)
    val ordinal = (index + FIRST_PAGE_ORDINAL).toString().padStart(digits, '0')
    val extension = EntryFilter.extensionOf(originalName)
    return if (extension.isEmpty()) ordinal else "$ordinal.$extension"
}

/**
 * Writes [pages] to [out] as a CBZ — a ZIP of images in reading order (§5.2, §6 M5).
 *
 * **Entries are STORED, not deflated.** Every format Absolutex treats as a page is already
 * compressed: deflating a JPEG or a WebP spends the CPU of a second compression pass to produce
 * output that is typically a little *larger* than the input. Storing also leaves the bytes
 * contiguous, so a reader can map a page rather than inflate it.
 *
 * That choice is what forces the buffer below: a STORED entry's size and CRC go in its local
 * header, *before* its data, so the bytes have to be in hand to be described. One page is held at
 * a time, so the cost is the largest page rather than the book.
 *
 * [out] is written but not closed — the caller owns it, and only the caller knows whether a
 * partial archive should be kept or discarded. On a read failure this throws with the archive
 * unfinished and no central directory written, which is deliberate: a CBZ that silently skipped
 * the pages it could not read would be a quietly wrong book rather than a failed export.
 *
 * @throws java.io.IOException if a page cannot be read or [out] cannot be written.
 */
fun writeCbz(pages: List<CbzPage>, out: OutputStream) {
    val zip = ZipOutputStream(out)
    pages.forEachIndexed { index, page ->
        val bytes = page.open().use { it.readBytes() }
        zip.putNextEntry(storedEntry(cbzEntryName(index, pages.size, page.entryName), bytes))
        zip.write(bytes)
        zip.closeEntry()
    }
    // finish, not close: writes the central directory and leaves [out] open for its owner.
    zip.finish()
}

/** A STORED entry fully described up front, as the ZIP format requires for an undeflated one. */
private fun storedEntry(name: String, bytes: ByteArray): ZipEntry = ZipEntry(name).apply {
    method = ZipEntry.STORED
    size = bytes.size.toLong()
    compressedSize = bytes.size.toLong()
    crc = CRC32().apply { update(bytes) }.value
    setLastModifiedTime(FIXED_ENTRY_TIME)
}
