// Host-side JNI test for solid 7z: nativeProbeSolid and nativeStreamEntries. Compiled as
// com.absolutex.source.libarchive.LibArchive by tools/test-archive-solid.sh, so it binds the real
// native methods of archive_jni.c and runs under -Xcheck:jni.
package com.absolutex.source.libarchive;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LibArchive {
    static { System.loadLibrary("absolutex_archive"); }

    static native byte[][] nativeList(int fd, boolean[] complete, boolean[] encrypted, byte[] password)
        throws IOException;
    static native byte[] nativeExtract(int fd, int ordinal, byte[] password) throws IOException;
    static native boolean nativeProbeSolid(int fd, long[] totalBytes);
    static native int nativeStreamEntries(int fd, boolean[] wanted, long maxBytes, byte[] password,
                                          EntrySink sink);

    static final int COMPLETE = 0, STOPPED = 1, FAILED = 2, LIMIT = 3;

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static int descriptor(FileInputStream in) throws Exception {
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        return field.getInt(in.getFD());
    }

    interface WithFd { void run(int fd) throws Exception; }

    static void with(Path file, WithFd body) throws Exception {
        try (FileInputStream in = new FileInputStream(file.toFile())) { body.run(descriptor(in)); }
    }

    static boolean[] all(int n) { boolean[] mask = new boolean[n]; Arrays.fill(mask, true); return mask; }

    static int openFds() { String[] names = new File("/dev/fd").list(); return names == null ? -1 : names.length; }

    /** Records every delivery; stops after [stopAfter] entries (or never when negative). */
    static final class Recorder implements EntrySink {
        final List<Integer> ordinals = new ArrayList<>();
        final List<byte[]> bytes = new ArrayList<>();
        final int stopAfter;
        Recorder(int stopAfter) { this.stopAfter = stopAfter; }
        @Override public boolean onEntry(int ordinal, byte[] data) {
            ordinals.add(ordinal);
            bytes.add(data);
            return stopAfter < 0 || ordinals.size() < stopAfter;
        }
    }

    static long declaredTotal(int fd) throws Exception {
        boolean[] complete = {false}, encrypted = {false};
        byte[][] names = nativeList(fd, complete, encrypted, null);
        long total = 0;
        for (int i = 0; i < names.length; i++) total += nativeExtract(fd, i, null).length;
        return total;
    }

    static void probe(Path solid, Path nonsolid, Path zip, String assets) throws Exception {
        with(solid, fd -> {
            long[] total = {-1};
            check(nativeProbeSolid(fd, total), "generated solid 7z must probe as solid");
            check(total[0] == declaredTotal(fd), "probe total must equal the sum of entry sizes");
        });
        with(nonsolid, fd -> {
            long[] total = {-1};
            check(!nativeProbeSolid(fd, total), "non-solid 7z must not probe as solid");
            check(total[0] > 0, "non-solid still reports its declared total");
        });
        with(zip, fd -> {
            long[] total = {-1};
            check(!nativeProbeSolid(fd, total), "a zip is never solid");
            check(total[0] == 0, "a zip reports no total");
        });
        // The committed fixtures: tiny (two 1x1 PNGs and a ComicInfo), so this proves the answer
        // does not depend on size, only on the block layout.
        with(Path.of(assets, "lzma2-solid.cb7"), fd -> {
            check(nativeProbeSolid(fd, new long[1]), "committed solid fixture");
        });
        with(Path.of(assets, "lzma2-nonsolid.cb7"), fd -> {
            check(!nativeProbeSolid(fd, new long[1]), "committed non-solid fixture");
        });
        with(Path.of(assets, "encrypted-content.cb7"), fd -> {
            check(!nativeProbeSolid(fd, new long[1]), "encrypted 7z probes false, never throws");
        });
        with(Path.of(assets, "encrypted-header.cb7"), fd -> {
            check(!nativeProbeSolid(fd, new long[1]), "header-encrypted 7z probes false, never throws");
        });
    }

    static void stream(Path solid, Path corrupt) throws Exception {
        with(solid, fd -> {
            boolean[] complete = {false}, encrypted = {false};
            int n = nativeList(fd, complete, encrypted, null).length;

            Recorder every = new Recorder(-1);
            check(nativeStreamEntries(fd, all(n), 1L << 40, null, every) == COMPLETE, "full pass completes");
            check(every.ordinals.size() == n, "every entry delivered once");
            for (int i = 0; i < n; i++) {
                check(every.ordinals.get(i) == i, "delivered in archive order");
                check(Arrays.equals(every.bytes.get(i), nativeExtract(fd, i, null)),
                    "streamed bytes equal single extract for ordinal " + i);
            }

            boolean[] some = new boolean[n];
            some[2] = true; some[n - 1] = true;
            Recorder subset = new Recorder(-1);
            check(nativeStreamEntries(fd, some, 1L << 40, null, subset) == COMPLETE, "subset completes");
            check(subset.ordinals.equals(Arrays.asList(2, n - 1)), "only wanted ordinals are delivered");
            check(Arrays.equals(subset.bytes.get(1), nativeExtract(fd, n - 1, null)), "last entry intact");

            boolean[] head = new boolean[n];
            head[0] = true;
            head[1] = true;
            Recorder early = new Recorder(-1);
            check(nativeStreamEntries(fd, head, 1L << 40, null, early) == COMPLETE, "prefix completes");
            check(early.ordinals.size() == 2, "the pass ends after the last wanted entry");

            check(nativeStreamEntries(fd, new boolean[n], 1L << 40, null, new Recorder(-1)) == COMPLETE,
                "nothing wanted is trivially complete");

            Recorder stop = new Recorder(3);
            check(nativeStreamEntries(fd, all(n), 1L << 40, null, stop) == STOPPED, "sink stop is reported");
            check(stop.ordinals.size() == 3, "no delivery after a stop");

            Recorder limited = new Recorder(-1);
            long onePage = every.bytes.get(0).length;
            check(nativeStreamEntries(fd, all(n), onePage * 3, null, limited) == LIMIT, "byte ceiling hit");
            check(limited.ordinals.size() <= 3 && !limited.ordinals.isEmpty(), "delivery stops at the ceiling");

            check(nativeStreamEntries(fd, new boolean[0], 1L << 40, null, new Recorder(-1)) == FAILED,
                "an empty mask is refused");
            check(nativeStreamEntries(fd, all(n), 0, null, new Recorder(-1)) == FAILED,
                "a zero ceiling is refused");

            try {
                nativeStreamEntries(fd, all(n), 1L << 40, null, (ordinal, data) -> {
                    throw new IllegalStateException("sink failure");
                });
                check(false, "a sink exception must propagate");
            } catch (IllegalStateException expected) {
                check("sink failure".equals(expected.getMessage()), "the sink's own exception arrives");
            }
        });

        with(corrupt, fd -> {
            boolean[] complete = {false}, encrypted = {false};
            byte[][] names;
            try {
                names = nativeList(fd, complete, encrypted, null);
            } catch (IOException e) {
                return;   // header lost with the tail: nothing to stream, which is the same verdict
            }
            Recorder rec = new Recorder(-1);
            int end = nativeStreamEntries(fd, all(names.length), 1L << 40, null, rec);
            check(end == FAILED || end == COMPLETE, "a corrupt archive ends, never hangs or crashes");
            for (int i = 0; i < rec.ordinals.size(); i++) {
                byte[] got = rec.bytes.get(i);
                if (got != null) {
                    check(Arrays.equals(got, nativeExtract(fd, rec.ordinals.get(i), null)),
                        "what a torn archive did deliver is exact");
                }
            }
            int torn = 0;
            for (byte[] got : rec.bytes) if (got == null) torn++;
            System.out.println("corrupt: torn=" + torn + " end=" + end + " delivered=" + rec.ordinals.size() + "/" + names.length);
        });
    }

    static void leaks(Path solid, Path nonsolid) throws Exception {
        int before = openFds();
        for (int i = 0; i < 300; i++) {
            for (Path file : new Path[] {solid, nonsolid}) {
                with(file, fd -> {
                    nativeProbeSolid(fd, new long[1]);
                    int n = nativeList(fd, new boolean[1], new boolean[1], null).length;
                    nativeStreamEntries(fd, all(n), 1L << 40, null, new Recorder(1));
                });
            }
        }
        int after = openFds();
        check(before < 0 || after == before, "descriptor leak: " + before + " -> " + after);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String assets = args[1];
        Path solid = dir.resolve("solid.7z"), nonsolid = dir.resolve("nonsolid.7z");
        Path zip = dir.resolve("plain.zip"), corrupt = dir.resolve("corrupt.7z");
        probe(solid, nonsolid, zip, assets);
        stream(solid, corrupt);
        leaks(solid, nonsolid);
        System.out.println("PASS: solid 7z probe (generated, committed, zip, encrypted) and single-pass "
            + "stream (order, subset, stop, ceiling, sink exception, corrupt, no descriptor leak)");
    }
}
