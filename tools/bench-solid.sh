#!/bin/sh
# Measures solid-7z paging on the host with the REAL native library and the real Kotlin sources
# (LibArchiveSource, SolidPass, ...), before and after the decode-once cache.
#
#   tools/bench-solid.sh [pages] [page-KiB]      defaults: 300 pages of 1024 KiB
#
# Needs 7zz (brew install sevenzip) and kotlinc (KOTLINC=/path/to/kotlinc; Maven Central's
# kotlin-compiler zip works, no Android SDK or Gradle involved). Generates the corpus in build/,
# prints tab-separated `label<TAB>ms` for "before" (no cache: today's behaviour) then "after", and
# deletes the corpus. The android.os classes the source touches are stubbed here, nowhere else.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PAGES=${1:-300}
KIB=${2:-1024}
OUT="$ROOT/build/bench-solid"
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
ARCHIVE_PREFIX=${ARCHIVE_PREFIX:-/opt/homebrew/opt/libarchive}
KOTLINC=${KOTLINC:-kotlinc}
KOTLIN_LIB=$(dirname "$(command -v "$KOTLINC")")/../lib
rm -rf "$OUT"
mkdir -p "$OUT/android" "$OUT/stubs/android/os" "$OUT/corpus" "$OUT/classes"
trap 'rm -rf "$OUT"' EXIT

python3 "$ROOT/tools/make-solid-corpus.py" "$OUT/corpus" --pages "$PAGES" --page-kib "$KIB"

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

cat > "$OUT/stubs/android/os/ParcelFileDescriptor.java" <<'JAVA'
package android.os;
import java.io.*;
public class ParcelFileDescriptor implements Closeable {
    public static final int MODE_READ_ONLY = 0x10000000;
    private final FileInputStream in; private final int fd;
    private ParcelFileDescriptor(FileInputStream in, int fd) { this.in = in; this.fd = fd; }
    public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
        FileInputStream in = new FileInputStream(file);
        try {
            java.lang.reflect.Field f = FileDescriptor.class.getDeclaredField("fd");
            f.setAccessible(true);
            return new ParcelFileDescriptor(in, f.getInt(in.getFD()));
        } catch (ReflectiveOperationException | IOException e) { throw new RuntimeException(e); }
    }
    public int getFd() { return fd; }
    @Override public void close() { try { in.close(); } catch (IOException e) { throw new RuntimeException(e); } }
}
JAVA
cat > "$OUT/stubs/android/os/Trace.java" <<'JAVA'
package android.os;
public final class Trace { public static void beginSection(String n) {} public static void endSection() {} }
JAVA
"$JAVA_HOME/bin/javac" -d "$OUT/classes" "$OUT"/stubs/android/os/*.java

"$KOTLINC" -jvm-target 21 -d "$OUT/classes" -cp "$OUT/classes" \
    "$ROOT/core/model/src/main/kotlin" "$ROOT/source/api/src/main/kotlin" \
    "$ROOT/source/libarchive/src/main/kotlin" "$ROOT/tools/SolidBench.kt"

run() {
    "$JAVA_HOME/bin/java" -Xmx1g --add-opens java.base/java.io=ALL-UNNAMED -Djava.library.path="$OUT" \
        -cp "$OUT/classes:$KOTLIN_LIB/kotlin-stdlib.jar" SolidBenchKt \
        "$OUT/corpus/solid.7z" "$OUT/corpus/pages" "$OUT/work-$1" "$1" 20
}
echo "corpus: $PAGES pages of $KIB KiB, solid LZMA2 ($(du -h "$OUT/corpus/solid.7z" | cut -f1))"
echo "--- before (no cache: today's behaviour)"
run before
echo "--- after (decode-once cache)"
run after
