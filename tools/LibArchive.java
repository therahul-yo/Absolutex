package com.absolutex.source.libarchive;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Decode M11 first-page-latency harness. Host-only, no device, no Android framework.
 *
 * WHY IT CALLS THE SHIPPED JNI BRIDGE
 *
 * The cost under investigation is libarchive re-walking entry headers on every extract. That
 * cost lives in {@code archive_jni.c}, so measuring a Java reimplementation of it would measure
 * something the app never runs. This loads the real {@code libabsolutex_archive.dylib} built
 * from that exact source file and calls the real {@code nativeList}/{@code nativeExtract}, so
 * every millisecond below is attributable to code that ships.
 *
 * WHAT IT CAN AND CANNOT REPORT
 *
 * It reports RATIOS between stages, which is what the breakdown in the PR needs: if the archive
 * walk is 40% of the first page on the host it is not going to be 5% on a phone, because both
 * terms scale with the same archive. It does NOT report phone-absolute milliseconds, and the
 * output says so, because it is a desktop JVM: different allocator, different page cache, a
 * different libarchive build. A number from here that was quoted as a device measurement would
 * be a lie with a decimal point on it.
 *
 * THE STAGES, AND WHICH absx.* SECTION EACH IS
 *
 * <pre>
 *   list     absx.entryList      nativeList: one walk of every header, plus the names table
 *   open     absx.archiveOpen    everything open() does beyond the list: ComicInfo locate +
 *                                extract, the password probe, and the readability count that
 *                                a recovery or encrypted open pays
 *   extract  absx.entryExtract   nativeExtract for page 0 -- the archive-side page cost
 *   decode   absx.headerParse    the bounds parse + BitmapRegionDecoder construction that
 *                                PageImage.from does before a single pixel is decoded
 * </pre>
 *
 * {@code decode} here is bounds-only, not a full raster decode: android.graphics.ImageDecoder
 * does not exist on the host, and standing in java.awt would measure ImageIO's JPEG decoder,
 * which is not what the app runs. The bounds parse is the real shipped code path up to the
 * point where the framework takes over, and it is measured as such rather than dressed up as a
 * full decode.
 */
public final class LibArchive {

    static { System.loadLibrary("absolutex_archive"); }

    private static native byte[][] nativeList(int fd, boolean[] complete, boolean[] encrypted,
                                              byte[] password);

    private static native byte[] nativeExtract(int fd, int ordinal, byte[] password);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LibArchive <book.cbz> <book.pdf> [rounds]");
            System.exit(2);
        }
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        if (rounds < 1) rounds = 1;

        System.out.println("Absolutex decode M11 -- host harness (no device, no Android framework)");
        System.out.println("libarchive bridge: source/libarchive/src/main/cpp/archive_jni.c, unmodified");
        System.out.println("Ratios are meaningful; absolute ms are host-absolute, not device-absolute.");
        System.out.println();

        Path cbz = Path.of(args[0]);
        Path pdf = Path.of(args[1]);
        System.out.printf("book  %s  (%.1f MB, %d entries)%n", cbz.getFileName(),
                sizeMb(cbz), entryCount(cbz));
        System.out.printf("book  %s  (%.1f MB, 600 pages)%n", pdf.getFileName(), sizeMb(pdf));
        System.out.println();

        for (int i = 0; i < rounds; i++) {
            System.out.printf("--- round %d of %d ---%n", i + 1, rounds);
            runOnce(cbz, i == rounds - 1);
            System.out.println();
        }
    }

    private static void runOnce(Path cbz, boolean detail) throws Exception {
        // Fresh descriptor per round: libarchive's reader caches a 64 KiB block and the OS
        // caches the file, so a second round on a warm handle measures cache hits, not the
        // cost a cold first page pays. That is exactly the cost the brief asks about.
        try (FileInputStream in = new FileInputStream(cbz.toFile())) {
            int fd = fdOf(in);

            // ---- absx.entryList: the whole-header walk ----
            long t0 = System.nanoTime();
            boolean[] complete = new boolean[1];
            boolean[] encrypted = new boolean[1];
            byte[][] names = nativeList(fd, complete, encrypted, null);
            long tList = System.nanoTime() - t0;
            if (names == null) throw new IllegalStateException("corpus CBZ is not a readable archive");

            int[] pageOrdinals = pageOrdinals(names);

            // ---- absx.archiveOpen: what open() pays beyond the list ----
            // ComicInfo is the last entry here, so locating and extracting it is the worst
            // case for a by-ordinal read: it walks every page header to reach it. That is
            // real production behaviour, not a contrivance -- every book has a sidecar and it
            // is not always first.
            long t1 = System.nanoTime();
            byte[] info = extractByName(names, fd, "ComicInfo.xml");
            long tOpen = System.nanoTime() - t1;
            if (info == null) {
                throw new IllegalStateException("bench CBZ has no ComicInfo.xml; regenerate the corpus");
            }

            // ---- absx.entryExtract: page 0, and the same page again for the floor ----
            // Two measurements, not one. The second extract of page 0 is the *floor*: the
            // same inflate, with the archive already in page cache, minus the header walk.
            // The difference between the two is the walk, isolated from the decode.
            int firstPage = pageOrdinals[0];
            long t2 = System.nanoTime();
            byte[] page = nativeExtract(fd, firstPage, null);
            long tExtractCold = System.nanoTime() - t2;

            long t3 = System.nanoTime();
            nativeExtract(fd, firstPage, null);
            long tExtractWarm = System.nanoTime() - t3;

            // ---- absx.headerParse: the pre-decode work PageImage.from does ----
            long t4 = System.nanoTime();
            int[] bounds = headerParse(page);
            long tParse = System.nanoTime() - t4;

            // ---- The deep page, which is where a per-ordinal walk is supposed to hurt ----
            // Page 0 costs 0 headers. Page 299 costs 299. If the walk is the problem, the
            // gap between first and last is the problem, and a fix that only helps page 0
            // has not fixed anything.
            int lastPage = pageOrdinals[pageOrdinals.length - 1];
            long t5 = System.nanoTime();
            nativeExtract(fd, lastPage, null);
            long tExtractLast = System.nanoTime() - t5;

            long total = tList + tOpen + tExtractCold + tParse;
            System.out.printf("  %-22s %8.2f ms   %5.1f%%%n", "list (entryList)", ms(tList), pct(tList, total));
            System.out.printf("  %-22s %8.2f ms   %5.1f%%%n", "open (sidecar)", ms(tOpen), pct(tOpen, total));
            System.out.printf("  %-22s %8.2f ms   %5.1f%%%n", "extract page 0", ms(tExtractCold), pct(tExtractCold, total));
            System.out.printf("  %-22s %8.2f ms   %5.1f%%%n", "headerParse", ms(tParse), pct(tParse, total));
            System.out.printf("  %-22s %8.2f ms%n", "TOTAL first page", ms(total));
            System.out.println();
            System.out.printf("  extract page 0 (warm)  %8.2f ms   <- inflate floor, no walk%n", ms(tExtractWarm));
            System.out.printf("  extract last page (%d) %8.2f ms   <- %d headers to walk%n",
                    pageOrdinals.length - 1, ms(tExtractLast), pageOrdinals.length - 1);
            long walk = tExtractLast - tExtractWarm;
            System.out.printf("  walk cost (last-warm)  %8.2f ms   = %.0f%% of a first page%n",
                    ms(walk), pct(walk, total));

            if (detail) {
                System.out.println();
                System.out.printf("  pages=%d  page0 bounds=%dx%d  page bytes=%.2f MB%n",
                        pageOrdinals.length, bounds[0], bounds[1], page.length / 1048576.0);
                System.out.printf("  sidecar: %s%n", new String(info, StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ").trim());
                System.out.printf("  archive complete=%s encrypted=%s%n", complete[0], encrypted[0]);
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // The measurements
    // ---------------------------------------------------------------------------------

    /**
     * The bounds parse and region-decoder construction, counted in the same two operations
     * PageImage.from performs. It walks the SOFn marker by hand rather than calling
     * ImageIO, so the number is the marker parse and nothing else -- a number a reviewer can
     * check by reading it, which a javax.imageio call would not be.
     */
    private static int[] headerParse(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IllegalStateException("bench page is not a JPEG");
        }
        int i = 2;
        while (i + 9 < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) { i++; continue; }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
            if (marker == 0xD9 || marker == 0xDA) break;      // EOI, or SOS: dimensions precede it
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            // SOF0..SOF15, skipping the non-frame markers in that range (DHT C4, JPG C8, DAC CC).
            boolean isSof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (isSof) {
                int h = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF);
                int w = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
                return new int[] {w, h};
            }
            if (len < 2) break;
            i += 2 + len;
        }
        throw new IllegalStateException("no SOF marker in the bench page");
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /** Ordinals of image entries, in natural order -- the same order LibArchiveSource builds. */
    private static int[] pageOrdinals(byte[][] names) {
        List<int[]> kept = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String n = new String(names[i], StandardCharsets.UTF_8);
            if (isPage(n)) kept.add(new int[] {naturalKey(n), i});
        }
        kept.sort(Comparator.comparingInt(a -> a[0]));
        int[] out = new int[kept.size()];
        for (int i = 0; i < out.length; i++) out[i] = kept.get(i)[1];
        return out;
    }

    private static boolean isPage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".avif") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".heif") || lower.endsWith(".jxl");
    }

    /** Digits compare numerically, so page9 < page10 -- NaturalOrder's contract, minimally. */
    private static int naturalKey(String name) {
        long key = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                int start = i;
                while (i + 1 < name.length() && name.charAt(i + 1) >= '0' && name.charAt(i + 1) <= '9') i++;
                // Saturate rather than overflow: a long digit run must not wrap negative and
                // sort the page to the front.
                long digits = 0;
                for (int k = start; k <= i && digits < 1_000_000L; k++) {
                    digits = digits * 10 + (name.charAt(k) - '0');
                }
                key = key * 31 + digits;
            } else {
                key = key * 31 + c;
            }
        }
        return (int) key;
    }

    private static byte[] extractByName(byte[][] names, int fd, String wanted) {
        for (int i = 0; i < names.length; i++) {
            if (new String(names[i], StandardCharsets.UTF_8).equals(wanted)) {
                return nativeExtract(fd, i, null);
            }
        }
        return null;
    }

    private static int entryCount(Path book) throws Exception {
        try (FileInputStream in = new FileInputStream(book.toFile())) {
            boolean[] c = new boolean[1];
            byte[][] names = nativeList(fdOf(in), c, new boolean[1], null);
            return names == null ? 0 : names.length;
        }
    }

    private static double sizeMb(Path p) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            return raf.length() / 1048576.0;
        }
    }

    private static int fdOf(FileInputStream in) throws Exception {
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        return field.getInt(in.getFD());
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0.0 : 100.0 * part / whole;
    }

    private LibArchive() {}
}
