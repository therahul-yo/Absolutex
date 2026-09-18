#!/bin/sh
# Real JNI on the host JVM, no Android device or generated hostile corpus required.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/archive-password-host"
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
ARCHIVE_PREFIX=${ARCHIVE_PREFIX:-/opt/homebrew/opt/libarchive}
mkdir -p "$OUT/android" "$OUT/com/absolutex/source/libarchive"
cat > "$OUT/android/log.h" <<'HEADER'
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void) priority; (void) tag; (void) format; return 0;
}
HEADER
for type in PasswordRequiredException WrongPasswordException UnsupportedEncryptionException; do
    cat > "$OUT/com/absolutex/source/libarchive/$type.java" <<JAVA
package com.absolutex.source.libarchive;
public class $type extends java.io.IOException {
    public $type(String message) { super(message); }
}
JAVA
done
cat > "$OUT/com/absolutex/source/libarchive/LibArchive.java" <<'JAVA'
package com.absolutex.source.libarchive;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public class LibArchive {
    static { System.loadLibrary("absolutex_archive"); }
    static native byte[][] nativeList(int fd, boolean[] complete, boolean[] encrypted, byte[] password)
        throws IOException;
    static native byte[] nativeExtract(int fd, int ordinal, byte[] password) throws IOException;
    interface Check { void run() throws IOException; }
    static void expect(Class<? extends IOException> type, Check check) throws IOException {
        try { check.run(); } catch (IOException e) {
            if (e.getClass() != type) throw e;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    static int descriptor(FileInputStream in) throws Exception {
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        return field.getInt(in.getFD());
    }
    public static void main(String[] args) throws Exception {
        byte[] password = "corpus-only".getBytes(StandardCharsets.UTF_8);
        byte[] wrong = "not-the-password".getBytes(StandardCharsets.UTF_8);
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        byte[] info = "<ComicInfo><Series>Encrypted fixture</Series><PageCount>1</PageCount></ComicInfo>"
            .getBytes(StandardCharsets.UTF_8);
        byte[] fixture = Base64.getMimeDecoder().decode(Files.readAllBytes(Path.of(args[0])));
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixture));
        if (!digest.equals("c685663840fc953c7c4e0b70e324400317d97130b4707d3a779cc2afa9c3848b")) {
            throw new AssertionError("fixture changed");
        }
        Path encryptedFile = Path.of(args[1], "encrypted-zipcrypto.cbz");
        Files.write(encryptedFile, fixture);
        try (FileInputStream in = new FileInputStream(encryptedFile.toFile())) {
            int fd = descriptor(in);
            boolean[] complete = {false}, encrypted = {false};
            expect(PasswordRequiredException.class, () -> nativeList(fd, complete, encrypted, null));
            expect(PasswordRequiredException.class, () -> nativeExtract(fd, 0, null));
            expect(WrongPasswordException.class, () -> nativeList(fd, complete, encrypted, wrong));
            expect(WrongPasswordException.class, () -> nativeExtract(fd, 0, wrong));
            byte[][] names = nativeList(fd, complete, encrypted, password);
            if (!complete[0] || !encrypted[0] || names.length != 2) throw new AssertionError("listing");
            if (!Arrays.equals(png, nativeExtract(fd, 0, password))) throw new AssertionError("PNG mismatch");
            if (!Arrays.equals(info, nativeExtract(fd, 1, password))) throw new AssertionError("metadata mismatch");
            // No native handle/global password may unlock a subsequent passwordless call.
            expect(PasswordRequiredException.class, () -> nativeList(fd, complete, encrypted, null));
        }
        Path ordinary = Path.of(args[1], "ordinary.cbz");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(ordinary))) {
            out.putNextEntry(new ZipEntry("001.png")); out.write(png); out.closeEntry();
        }
        try (FileInputStream in = new FileInputStream(ordinary.toFile())) {
            int fd = descriptor(in);
            boolean[] complete = {false}, encrypted = {true};
            if (nativeList(fd, complete, encrypted, null).length != 1 || !complete[0] || encrypted[0]) {
                throw new AssertionError("ordinary listing regression");
            }
            if (!Arrays.equals(png, nativeExtract(fd, 0, null))) throw new AssertionError("ordinary payload");
        }
        Arrays.fill(password, (byte) 0); Arrays.fill(wrong, (byte) 0);
        System.out.println("PASS: required, wrong, correct PNG + metadata, no retained password, ordinary ZIP");
    }
}
JAVA
clang -shared -fPIC -Os -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" -I"$OUT" -I"$ARCHIVE_PREFIX/include" \
    "$ROOT/source/libarchive/src/main/cpp/archive_jni.c" \
    -L"$ARCHIVE_PREFIX/lib" -larchive -o "$OUT/libabsolutex_archive.dylib"
"$JAVA_HOME/bin/javac" "$OUT"/com/absolutex/source/libarchive/*.java
"$JAVA_HOME/bin/java" -Xcheck:jni --add-opens java.base/java.io=ALL-UNNAMED -Djava.library.path="$OUT" \
    -cp "$OUT" com.absolutex.source.libarchive.LibArchive \
    "$ROOT/source/libarchive/src/androidTest/assets/encrypted-zipcrypto.cbz.b64" "$OUT"
