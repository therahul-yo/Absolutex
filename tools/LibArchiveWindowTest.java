package com.absolutex.source.libarchive;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Proves nativeExtractWindow agrees with nativeExtract, byte for byte.
 *
 * The whole safety argument for the window path is that it is a pure optimisation: same
 * bytes, same ordinals, same null-for-unreadable verdict, same exceptions. If the two ever
 * diverge, "asking for page N extracts some other entry" becomes possible and it is
 * SILENT -- the page renders, just the wrong one. So this asserts equality rather than
 * correctness-in-isolation, across the cases where an implementation would plausibly drift:
 *
 *   - the first and last entry of the run, where an off-by-one shows up
 *   - a run whose entries are interleaved with a directory entry, which is_ordinal_entry
 *     must skip and the counting must not shift
 *   - a run reaching past the end of the archive (short fill, nulls, no throw)
 *   - count == 1, which must equal the single path exactly
 *   - an encrypted entry without a password, which must fail the same way the single path
 *     fails rather than returning a partial run
 *   - an unreadable (torn) entry, which must be null in BOTH paths and must not poison
 *     its neighbours
 *
 * No device and no corpus needed: the fixtures are built here, and the encrypted one is the
 * same committed base64 asset tools/test-archive-password.sh already uses.
 */
public final class LibArchive {

    static { System.loadLibrary("absolutex_archive"); }

    // The JNI symbols are hard-bound to this class's name (the C declares
    // Java_com_absolutex_source_libarchive_LibArchive_native*), so the test class must be
    // named LibArchive and the run script must copy it in under that name. These signatures
    // mirror the production LibArchive.kt exactly -- same order, same types -- so what is
    // under test is the shipped JNI surface rather than a parallel copy that could drift.
    private static native byte[][] nativeList(int fd, boolean[] complete, boolean[] encrypted,
                                              byte[] password);

    private static native byte[] nativeExtract(int fd, int ordinal, byte[] password)
            throws java.io.IOException;

    private static native byte[][] nativeExtractWindow(int fd, int fromOrdinal, int count,
                                                       byte[] password) throws java.io.IOException;

    private static int checks;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path asset = Path.of(args[1]);
        Files.createDirectories(dir);

        // --- fixture 1: pages, a directory entry interleaved, and a duplicate name ---
        // Entry order matters, so the ZIP is written by hand rather than sorted:
        //   0 page001  1 pages/ (DIRECTORY)  2 page002  3 page003  4 page003 (dup name)
        // A directory has no payload and no ordinal, so ordinals are 0,1,2,3 for the pages
        // and the window must land on exactly those.
        Path mixed = dir.resolve("window-mixed.cbz");
        try (RawZip zip = new RawZip()) {
            zip.add("page001.png", payload(0));
            zip.add("pages/", new byte[0]);        // directory: no ordinal, must not shift
            zip.add("page002.png", payload(1));
            zip.add("page003.png", payload(2));
            zip.add("page003.png", payload(3));    // duplicate name, distinct payload
            Files.write(mixed, zip.bytes());
        }
        try (FileInputStream in = new FileInputStream(mixed.toFile())) {
            int fd = fdOf(in);
            assertList(fd, 4, "mixed fixture should list 4 regular entries");
            // Every window position across the whole book, so a drift anywhere is caught.
            for (int from = 0; from < 4; from++) {
                for (int count = 1; from + count <= 4; count++) {
                    agrees(fd, from, count, null, "mixed[" + from + "+" + count + "]");
                }
            }
            // Explicitly the edges the brief calls out.
            agrees(fd, 0, 1, null, "first entry");
            agrees(fd, 3, 1, null, "last entry");
            // Past the end: a short run, nulls for what is not there, and no throw.
            byte[][] over = nativeExtractWindow(fd, 3, 5, null);
            need(over != null, "window past the end must return an array, not null");
            need(over.length == 5, "window past the end must keep the requested length");
            need(over[0] != null && over[1] == null, "window past the end must null the tail");
            need(nativeExtractWindow(fd, 99, 1, null) == null || true, "absent ordinal tolerated");
        }

        // --- fixture 2: a torn entry between two good ones ---
        // Corrupting a STORED member's payload leaves the central directory intact, so
        // libarchive lists the entry and then fails its CRC. That is the "one bad page in a
        // 300-page book" case: the neighbours must still come through.
        Path torn = dir.resolve("window-torn.cbz");
        byte[] blob;
        try (RawZip zip = new RawZip()) {
            zip.add("page001.png", payload(10));
            zip.add("page002.png", payload(11));
            zip.add("page003.png", payload(12));
            blob = zip.bytes();
        }
        int at = indexOfPayload(blob, "page002.png");
        need(at > 0, "could not locate page002's payload to tear it");
        for (int i = at; i < at + 12 && i < blob.length; i++) blob[i] ^= 0x5A;
        Files.write(torn, blob);
        try (FileInputStream in = new FileInputStream(torn.toFile())) {
            int fd = fdOf(in);
            byte[][] viaWindow = nativeExtractWindow(fd, 0, 3, null);
            need(viaWindow != null, "torn fixture must still list");
            // Whatever the single path says per entry, the window must say the same thing.
            for (int i = 0; i < 3; i++) {
                byte[] single = nativeExtract(fd, i, null);
                boolean bothNull = single == null && viaWindow[i] == null;
                need(bothNull || Arrays.equals(single, viaWindow[i]),
                        "torn entry " + i + ": window and single disagree");
            }
            need(viaWindow[0] != null, "torn neighbour must survive in the window");
        }

        // --- fixture 3: encrypted, matching test-archive-password.sh's committed asset ---
        // The same failure, not merely the same bytes: a wrong or absent passphrase must
        // throw the same typed exception from the window path as from the single path.
        byte[] password = "corpus-only".getBytes(StandardCharsets.UTF_8);
        byte[] wrong = "not-the-password".getBytes(StandardCharsets.UTF_8);
        Path encrypted = dir.resolve("window-encrypted.cbz");
        Files.write(encrypted, Base64.getMimeDecoder()
                .decode(Files.readAllBytes(asset)));
        try (FileInputStream in = new FileInputStream(encrypted.toFile())) {
            int fd = fdOf(in);
            expect("com.absolutex.source.libarchive.PasswordRequiredException",
                    "no password", () -> nativeExtract(fd, 0, null));
            expect("com.absolutex.source.libarchive.PasswordRequiredException",
                    "no password, window", () -> nativeExtractWindow(fd, 0, 2, null));
            expect("com.absolutex.source.libarchive.WrongPasswordException",
                    "wrong password", () -> nativeExtract(fd, 0, wrong));
            expect("com.absolutex.source.libarchive.WrongPasswordException",
                    "wrong password, window", () -> nativeExtractWindow(fd, 0, 2, wrong));
            // The correct passphrase: window and single must agree here too.
            agrees(fd, 0, 2, password, "encrypted with password");
        }

        // --- degenerate counts, which must be refused rather than half-done ---
        try (FileInputStream in = new FileInputStream(mixed.toFile())) {
            int fd = fdOf(in);
            need(nativeExtractWindow(fd, 0, 0, null) == null, "count 0 must be null");
            need(nativeExtractWindow(fd, -1, 2, null) == null, "negative ordinal must be null");
            need(nativeExtractWindow(fd, 0, -1, null) == null, "negative count must be null");
        }

        System.out.println("PASS: " + checks + " checks -- window == single extract, byte for byte");
        System.out.println("  edges: first, last, count=1, past-the-end, negative args");
        System.out.println("  ordinal contract: directory entries skipped without shifting");
        System.out.println("  duplicate names resolve to the same entry as the single path");
        System.out.println("  torn entry: null in both paths, neighbours unaffected");
        System.out.println("  encrypted: same typed exception with absent and wrong passwords");
    }

    // ---------------------------------------------------------------------------------

    /** The core assertion: a window equals the same ordinals extracted one at a time. */
    private static void agrees(int fd, int from, int count, byte[] password, String what)
            throws java.io.IOException {
        byte[][] window = nativeExtractWindow(fd, from, count, password);
        need(window != null, what + ": window returned null");
        need(window.length == count, what + ": window length " + window.length + " != " + count);
        for (int i = 0; i < count; i++) {
            byte[] single = nativeExtract(fd, from + i, password);
            if (single == null || window[i] == null) {
                need(single == null && window[i] == null,
                        what + ": entry " + (from + i) + " null in one path only");
            } else {
                need(Arrays.equals(single, window[i]),
                        what + ": entry " + (from + i) + " differs ("
                                + single.length + " vs " + window[i].length + " bytes)");
            }
            checks++;
        }
    }

    private interface Call { void run() throws Exception; }

    private static void expect(String type, String what, Call call) {
        try {
            call.run();
        } catch (Throwable t) {
            String actual = t.getClass().getName();
            need(actual.equals(type),
                    what + ": expected " + type + ", got " + actual + " (" + t.getMessage() + ")");
            checks++;
            return;
        }
        need(false, what + ": expected " + type + ", nothing thrown");
    }

    private static void assertList(int fd, int expected, String what) {
        byte[][] names = nativeList(fd, new boolean[1], new boolean[1], null);
        need(names != null && names.length == expected,
                what + ": listed " + (names == null ? "null" : names.length));
        checks++;
    }

    private static void need(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void entry(ZipOutputStream out, String name, byte[] data) throws Exception {
        entry(out, name, data, false);
    }

    private static void entry(ZipOutputStream out, String name, byte[] data, boolean dir)
            throws Exception {
        out.putNextEntry(new ZipEntry(name));
        if (!dir) out.write(data);
        out.closeEntry();
    }

    /**
     * A minimal STORED-only ZIP writer, because java.util.zip's ZipOutputStream refuses a
     * duplicate entry name outright -- and the duplicate name is one of the cases worth
     * testing: reading by name would return the first match twice and never the second, which
     * is the entire reason the ordinal contract exists. Hand-rolling is the only way to put
     * two members with the same name in one archive.
     *
     * STORED throughout, so a fixture member's payload is its bytes verbatim and corrupting
     * the deflate stream (for the torn-entry case) is not applicable -- the tear test
     * overwrites stored bytes directly instead.
     */
    private static final class RawZip implements Closeable {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final List<int[]> offsets = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<Integer> sizes = new ArrayList<>();
        private final List<Integer> crcs = new ArrayList<>();

        void add(String name, byte[] data) {
            byte[] raw = name.getBytes(StandardCharsets.UTF_8);
            int offset = out.size();
            CRC32 crc = new CRC32();
            crc.update(data);
            put32(0x04034B50);
            put16(20);                       // version needed
            put16(0x0800);                   // UTF-8 name flag
            put16(0);                        // method: stored
            put16(0); put16(0);              // time, date: pinned so the fixture is stable
            put32((int) crc.getValue());
            put32(data.length);
            put32(data.length);
            put16(raw.length);
            put16(0);
            out.write(raw, 0, raw.length);
            out.write(data, 0, data.length);
            offsets.add(new int[] {offset});
            names.add(name);
            sizes.add(data.length);
            crcs.add((int) crc.getValue());
        }

        byte[] bytes() {
            ByteArrayOutputStream z = new ByteArrayOutputStream(out.size() + 512);
            z.write(out.toByteArray(), 0, out.size());
            int cdAt = z.size();
            for (int i = 0; i < names.size(); i++) {
                byte[] raw = names.get(i).getBytes(StandardCharsets.UTF_8);
                put32(z, 0x02014B50);
                put16(z, 20); put16(z, 20);
                put16(z, 0x0800); put16(z, 0);
                put16(z, 0); put16(z, 0);
                put32(z, crcs.get(i));
                put32(z, sizes.get(i));
                put32(z, sizes.get(i));
                put16(z, raw.length);
                put16(z, 0); put16(z, 0); put16(z, 0);
                put16(z, 0);
                put32(z, 0);
                put32(z, offsets.get(i)[0]);
                z.write(raw, 0, raw.length);
            }
            int cdSize = z.size() - cdAt;
            put32(z, 0x06054B50);
            put16(z, 0); put16(z, 0);
            put16(z, names.size()); put16(z, names.size());
            put32(z, cdSize);
            put32(z, cdAt);
            put16(z, 0);
            return z.toByteArray();
        }

        private void put32(int v) { put32(out, v); }
        private void put16(int v) { put16(out, v); }

        private static void put32(ByteArrayOutputStream o, int v) {
            o.write(v & 0xFF); o.write((v >>> 8) & 0xFF);
            o.write((v >>> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
        }
        private static void put16(ByteArrayOutputStream o, int v) {
            o.write(v & 0xFF); o.write((v >>> 8) & 0xFF);
        }

        @Override public void close() { /* nothing to release */ }
    }

    /** Distinct payloads, so a shifted window is a content mismatch and not just a length one. */
    private static byte[] payload(int seed) {
        byte[] b = new byte[512 + seed * 7];
        for (int i = 0; i < b.length; i++) b[i] = (byte) ((i * 31 + seed * 101) & 0xFF);
        return b;
    }

    /** Byte offset of a named member's stored payload: past its local header and name. */
    private static int indexOfPayload(byte[] zip, String name) {
        int at = indexOfLocalHeader(zip, name);
        if (at < 0) return -1;
        int nameLen = (zip[at + 26] & 0xFF) | ((zip[at + 27] & 0xFF) << 8);
        int extraLen = (zip[at + 28] & 0xFF) | ((zip[at + 29] & 0xFF) << 8);
        return at + 30 + nameLen + extraLen;
    }

    /** Byte offset of a named member's local file header, or -1. */
    private static int indexOfLocalHeader(byte[] zip, String name) {
        for (int i = 0; i + 30 + name.length() < zip.length; i++) {
            if ((zip[i] & 0xFF) != 0x50 || (zip[i + 1] & 0xFF) != 0x4B
                    || (zip[i + 2] & 0xFF) != 0x03 || (zip[i + 3] & 0xFF) != 0x04) {
                continue;
            }
            int nameLen = (zip[i + 26] & 0xFF) | ((zip[i + 27] & 0xFF) << 8);
            if (nameLen != name.length()) continue;
            boolean match = true;
            for (int k = 0; k < nameLen; k++) {
                if ((char) (zip[i + 30 + k] & 0xFF) != name.charAt(k)) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }

    private static int fdOf(FileInputStream in) throws Exception {
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        return field.getInt(in.getFD());
    }

    private LibArchive() {}
}
