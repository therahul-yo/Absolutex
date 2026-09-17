#!/bin/sh
# Host-only JNI regression for the generated, ordinary truncated CBZ. No device needed.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/archive-recovery-host"
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
ARCHIVE_PREFIX=${ARCHIVE_PREFIX:-/opt/homebrew/opt/libarchive}
mkdir -p "$OUT/android" "$OUT/com/absolutex/source/libarchive"
cat > "$OUT/android/log.h" <<'HEADER'
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void) priority; (void) tag; (void) format; return 0;
}
HEADER
cat > "$OUT/com/absolutex/source/libarchive/LibArchive.java" <<'JAVA'
package com.absolutex.source.libarchive;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
public class LibArchive {
    static { System.loadLibrary("absolutex_archive"); }
    static native byte[][] nativeList(int fd, boolean[] complete, boolean[] encrypted, byte[] password);
    static native byte[] nativeExtract(int fd, int ordinal, byte[] password);
    public static void main(String[] args) throws Exception {
        try (FileInputStream in = new FileInputStream(args[0])) {
            Field field = FileDescriptor.class.getDeclaredField("fd");
            field.setAccessible(true);
            int fd = field.getInt(in.getFD());
            boolean[] complete = {true};
            byte[][] names = nativeList(fd, complete, new boolean[1], null);
            int listed = 0, readable = 0;
            for (int i = 0; i < names.length; i++) {
                String name = new String(names[i], StandardCharsets.UTF_8);
                if (name.endsWith(".png")) {
                    listed++;
                    if (nativeExtract(fd, i, null) != null) readable++;
                }
            }
            System.out.printf("listed=%d readable=%d%n", listed, readable);
            if (listed != 14 || readable != 13 || complete[0]) {
                throw new AssertionError("recovery must retain 14 slots but only 13 complete payloads");
            }
            String info = new String(nativeExtract(fd, 0, null), StandardCharsets.UTF_8);
            if (!info.contains("<PageCount>20</PageCount>")) throw new AssertionError("missing total");
        }
    }
}
JAVA
clang -shared -fPIC -Os -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" -I"$OUT" -I"$ARCHIVE_PREFIX/include" \
    "$ROOT/source/libarchive/src/main/cpp/archive_jni.c" \
    -L"$ARCHIVE_PREFIX/lib" -larchive -o "$OUT/libabsolutex_archive.dylib"
"$JAVA_HOME/bin/javac" "$OUT/com/absolutex/source/libarchive/LibArchive.java"
"$JAVA_HOME/bin/java" --add-opens java.base/java.io=ALL-UNNAMED -Djava.library.path="$OUT" \
    -cp "$OUT" com.absolutex.source.libarchive.LibArchive "$ROOT/build/corpus/11_truncated.cbz"
