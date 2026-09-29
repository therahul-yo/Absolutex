// Host-side JNI test for 7z (.cb7). Compiled as com.absolutex.source.libarchive.LibArchive by
// tools/test-archive-7z.sh, so it binds the real native methods of archive_jni.c.
package com.absolutex.source.libarchive;

import java.io.FileInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

public class LibArchive {
    static { System.loadLibrary("absolutex_archive"); }
    static native byte[][] nativeList(int fd, boolean[] complete, boolean[] encrypted, byte[] password)
        throws IOException;
    static native byte[] nativeExtract(int fd, int ordinal, byte[] password) throws IOException;
    static native byte[][] nativeExtractWindow(int fd, int from, int count, byte[] password)
        throws IOException;

    interface Check { void run() throws Exception; }

    static void expect(Class<? extends IOException> type, Check check) throws Exception {
        try { check.run(); } catch (IOException e) {
            if (e.getClass() != type) throw new AssertionError("wanted " + type.getSimpleName() + " got " + e, e);
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    static int descriptor(FileInputStream in) throws Exception {
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        return field.getInt(in.getFD());
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    // Fixtures were authored with 7-Zip 26.03 (`7zz a -t7z -m0=lzma2 ...`), two 1x1 PNG pages
    // and a ComicInfo.xml, and are pinned so a changed fixture cannot silently weaken the test.
    static final String PAGE1 = "2e9b06dc65a4dec84a3eb3124553ec93ca27c78221e64ab2177d0f1412cfcb20";
    static final String PAGE2 = "4621bdc6785587a1a242c7ea60fcc348ab29dca54ca73ea0cf34ee44093ca488";
    static final String INFO = "d958767b58305313ab819308462e91f97cfed4ceb3cd16c8b89870ff38fe3bea";

    static Path fixture(String assets, String out, String name, String sha) throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(Files.readAllBytes(Path.of(assets, "7z-" + name + ".cb7.b64")));
        check(sha256(bytes).equals(sha), "fixture " + name + " changed");
        Path file = Path.of(out, name + ".cb7");
        Files.write(file, bytes);
        return file;
    }

    static void plain(Path file) throws Exception {
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            int fd = descriptor(in);
            boolean[] complete = {false}, encrypted = {true};
            byte[][] names = nativeList(fd, complete, encrypted, null);
            check(complete[0] && !encrypted[0], "listing flags for " + file);
            check(names.length == 3, "entry count for " + file);
            check("001.png".equals(new String(names[0], StandardCharsets.UTF_8)), "first name");
            check(sha256(nativeExtract(fd, 0, null)).equals(PAGE1), "page 1 bytes");
            check(sha256(nativeExtract(fd, 1, null)).equals(PAGE2), "page 2 bytes");
            check(sha256(nativeExtract(fd, 2, null)).equals(INFO), "ComicInfo bytes");
            byte[][] window = nativeExtractWindow(fd, 0, 3, null);
            check(window.length == 3, "window length");
            check(sha256(window[0]).equals(PAGE1) && sha256(window[1]).equals(PAGE2)
                && sha256(window[2]).equals(INFO), "window bytes agree with single extracts");
            check(nativeExtract(fd, 3, null) == null, "out-of-range ordinal is null, not an error");
        }
    }

    static void encrypted(Path file, boolean headerEncrypted) throws Exception {
        byte[] password = "corpus-only".getBytes(StandardCharsets.UTF_8);
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            int fd = descriptor(in);
            boolean[] complete = {false}, encrypted = {false};
            // 7-Zip AES cannot be decrypted by libarchive, so a passphrase is never asked for:
            // the answer is "unsupported" with and without one, on every entry point.
            expect(UnsupportedEncryptionException.class, () -> nativeList(fd, complete, encrypted, null));
            expect(UnsupportedEncryptionException.class, () -> nativeList(fd, complete, encrypted, password));
            expect(UnsupportedEncryptionException.class, () -> nativeExtract(fd, 0, null));
            expect(UnsupportedEncryptionException.class, () -> nativeExtract(fd, 0, password));
            // With an encrypted header nothing can be listed, so no page window is ever asked
            // for; the window walk reports a short (all-null) run there, as for any unreadable book.
            if (headerEncrypted) return;
            expect(UnsupportedEncryptionException.class, () -> nativeExtractWindow(fd, 0, 2, null));
            expect(UnsupportedEncryptionException.class, () -> nativeExtractWindow(fd, 0, 2, password));
        }
    }

    public static void main(String[] args) throws Exception {
        String out = args[0], assets = args[1];
        plain(fixture(assets, out, "lzma2-nonsolid", SHA_NONSOLID));
        plain(fixture(assets, out, "lzma2-solid", SHA_SOLID));
        encrypted(fixture(assets, out, "encrypted-content", SHA_ENC_CONTENT), false);
        encrypted(fixture(assets, out, "encrypted-header", SHA_ENC_HEADER), true);
        System.out.println("PASS: LZMA2 non-solid and solid .cb7 list and extract (single and window); "
            + "content- and header-encrypted 7z report unsupported encryption with and without a password");
    }

    static final String SHA_NONSOLID = "54c865a7266a171ff66374da542a3f5dd9e9a71219503f82dc598ba02d4b6edc";
    static final String SHA_SOLID = "db0e958303da22448af62b536906ef38c660dababb059725d739f9b9778c8aa3";
    static final String SHA_ENC_CONTENT = "913c26f857974fae4217cf3c8bc0b9b519976dfe70eb9d8e1f416acad8daa72b";
    static final String SHA_ENC_HEADER = "efa311b4a2e818b3d4e1e4cd0325391ecb3fce8597c5eb7f50c4c547a8e8d646";
}
