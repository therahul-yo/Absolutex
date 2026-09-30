#!/bin/sh
# Real JNI on the host JVM: solid-7z detection (nativeProbeSolid) and the one-pass stream
# (nativeStreamEntries), under -Xcheck:jni.
#
# Needs `7zz` (brew install sevenzip) for the generated archives; the committed 7z fixtures cover
# the tiny cases. The generated archives live in build/ and are deleted afterwards.
# Like the sibling scripts, compiles archive_jni.c against a host libarchive (ARCHIVE_PREFIX) or
# uses an already built library (NATIVE_DIR).
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/archive-solid-host"
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
ASSETS="$ROOT/source/libarchive/src/androidTest/assets"
rm -rf "$OUT"
mkdir -p "$OUT/android" "$OUT/com/absolutex/source/libarchive" "$OUT/fixtures" "$OUT/corpus"
trap 'rm -rf "$OUT/corpus" "$OUT/fixtures"' EXIT
python3 "$ROOT/tools/make-solid-corpus.py" "$OUT/corpus" --pages 40 --page-kib 200
# A solid 7z whose FIRST entry declares 48 MB of zeros (a few KiB packed), then two small pages:
# the probe must give up on it (decoding past 32 MB at open), and the pass must refuse the entry.
mkdir -p "$OUT/corpus/huge"
head -c 50331648 /dev/zero > "$OUT/corpus/huge/000.bin"
head -c 65536 "$OUT/corpus/pages/001.png" > "$OUT/corpus/huge/001.png"
head -c 65536 "$OUT/corpus/pages/002.png" > "$OUT/corpus/huge/002.png"
(cd "$OUT/corpus/huge" && "$(command -v 7zz || command -v 7z)" a -t7z -m0=lzma2 -ms=on -mqs=off \
    ../huge.7z 000.bin 001.png 002.png > /dev/null)
for name in lzma2-solid lzma2-nonsolid encrypted-content encrypted-header; do
    base64 -d < "$ASSETS/7z-$name.cb7.b64" > "$OUT/fixtures/$name.cb7" 2>/dev/null ||
        base64 -D < "$ASSETS/7z-$name.cb7.b64" > "$OUT/fixtures/$name.cb7"
done
for type in PasswordRequiredException WrongPasswordException UnsupportedEncryptionException; do
    cat > "$OUT/com/absolutex/source/libarchive/$type.java" <<JAVA
package com.absolutex.source.libarchive;
public class $type extends java.io.IOException {
    public $type(String message) { super(message); }
}
JAVA
done
cat > "$OUT/com/absolutex/source/libarchive/EntrySink.java" <<'JAVA'
package com.absolutex.source.libarchive;
public interface EntrySink {
    boolean onEntry(int ordinal, byte[] data);
}
JAVA
cp "$ROOT/tools/LibArchiveSolidTest.java" "$OUT/com/absolutex/source/libarchive/LibArchive.java"
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
    com.absolutex.source.libarchive.LibArchive "$OUT/corpus" "$OUT/fixtures"
