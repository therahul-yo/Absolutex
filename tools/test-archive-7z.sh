#!/bin/sh
# Real JNI on the host JVM: 7z (.cb7) decode and the encrypted-7z error mapping.
#
# No device and no generated corpus: four tiny 7z fixtures are committed as base64 next to the
# ZipCrypto one (source/libarchive/src/androidTest/assets/7z-*.cb7.b64) and pinned by SHA-256
# in tools/LibArchiveSevenZipTest.java. They cover LZMA2 non-solid, LZMA2 solid, and 7-Zip AES
# with a plain and with an encrypted header (public test password `corpus-only`).
#
# The native library must be built WITH liblzma or the plain cases fail, which is the point.
# Two ways to get one:
#   - default: compile archive_jni.c against a libarchive that has LZMA (brew install libarchive
#     xz; override with ARCHIVE_PREFIX), the same way test-archive-password.sh does.
#   - NATIVE_DIR=<dir>: use an already built libabsolutex_archive.{dylib,so} from that directory,
#     for example one produced by configuring source/libarchive/src/main/cpp/CMakeLists.txt on
#     the host, which exercises the real FetchContent xz + libarchive wiring.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/archive-7z-host"
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
ASSETS="$ROOT/source/libarchive/src/androidTest/assets"
mkdir -p "$OUT/android" "$OUT/com/absolutex/source/libarchive"
for type in PasswordRequiredException WrongPasswordException UnsupportedEncryptionException; do
    cat > "$OUT/com/absolutex/source/libarchive/$type.java" <<JAVA
package com.absolutex.source.libarchive;
public class $type extends java.io.IOException {
    public $type(String message) { super(message); }
}
JAVA
done
cp "$ROOT/tools/LibArchiveSevenZipTest.java" "$OUT/com/absolutex/source/libarchive/LibArchive.java"
if [ -n "${NATIVE_DIR:-}" ]; then
    LIB_DIR="$NATIVE_DIR"
else
    ARCHIVE_PREFIX=${ARCHIVE_PREFIX:-/opt/homebrew/opt/libarchive}
    cat > "$OUT/android/log.h" <<'HEADER'
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void) priority; (void) tag; (void) format; return 0;
}
HEADER
    clang -shared -fPIC -Os -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
        -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" -I"$OUT" -I"$ARCHIVE_PREFIX/include" \
        "$ROOT/source/libarchive/src/main/cpp/archive_jni.c" \
        -L"$ARCHIVE_PREFIX/lib" -larchive -o "$OUT/libabsolutex_archive.dylib"
    LIB_DIR="$OUT"
fi
"$JAVA_HOME/bin/javac" -d "$OUT" "$OUT"/com/absolutex/source/libarchive/*.java
"$JAVA_HOME/bin/java" -Xcheck:jni --add-opens java.base/java.io=ALL-UNNAMED \
    -Djava.library.path="$LIB_DIR" -cp "$OUT" \
    com.absolutex.source.libarchive.LibArchive "$OUT" "$ASSETS"
