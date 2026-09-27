#!/bin/sh
# Real JNI on the host JVM: proves nativeExtractWindow agrees with nativeExtract.
#
# No device, no APK, and no generated corpus: the fixtures are built by the test itself and
# the encrypted one reuses the committed base64 asset test-archive-password.sh already pins.
# Runs the same clang -Werror the Android build uses, so a warning here is a warning there.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/archive-window-host"
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
cp "$ROOT/tools/LibArchiveWindowTest.java" "$OUT/com/absolutex/source/libarchive/LibArchive.java"
clang -shared -fPIC -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" -I"$OUT" -I"$ARCHIVE_PREFIX/include" \
    "$ROOT/source/libarchive/src/main/cpp/archive_jni.c" \
    -L"$ARCHIVE_PREFIX/lib" -larchive -o "$OUT/libabsolutex_archive.dylib"
"$JAVA_HOME/bin/javac" -d "$OUT" "$OUT"/com/absolutex/source/libarchive/*.java
# -Xcheck:jni: the window path holds a local reference per produced entry, and a leak or a
# stale one there is exactly the kind of bug that shows up as a crash much later on device.
"$JAVA_HOME/bin/java" -Xcheck:jni --add-opens java.base/java.io=ALL-UNNAMED \
    -Djava.library.path="$OUT" -cp "$OUT" \
    com.absolutex.source.libarchive.LibArchive \
    "$OUT" "$ROOT/source/libarchive/src/androidTest/assets/encrypted-zipcrypto.cbz.b64"
