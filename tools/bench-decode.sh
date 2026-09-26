#!/usr/bin/env bash
# Decode M11 first-page-latency and big-file-memory benchmark. Host-only: no device, no adb,
# no Gradle, no APK. The decode path's cost is dominated by libarchive header walking and by
# inflate, and both are plain host work, so measuring them on the Mac measures the same thing
# the phone spends its frame budget on.
#
# Why a host harness and not :benchmark (the instrumentation module): :benchmark needs a
# device, and the standing rule is emulator-only at best. Worse, the number that matters here
# -- how much of the first page is the archive walk rather than the decode -- is a RATIO
# between two host-measurable quantities, and a ratio measured on one machine is more
# trustworthy than two absolutes measured on two different ones. The absolute milliseconds on a
# OnePlus 11R are not what this harness reports, and it does not pretend to.
#
# It builds archive_jni.c unmodified and calls the real nativeList/nativeExtract through the
# real JNI signatures, so the cost being attributed is the shipped cost, not a model of it.
#
# Usage:
#   tools/bench-decode.sh                 # generate the corpus if absent, then run
#   tools/bench-decode.sh --regen         # force corpus regeneration
#   tools/bench-decode.sh --rounds 7      # more rounds for a tighter median
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

CORPUS="${CORPUS:-$ROOT/build/corpus}"
OUT="$ROOT/build/decode-bench"
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
ARCHIVE_PREFIX=${ARCHIVE_PREFIX:-/opt/homebrew/opt/libarchive}
ROUNDS=5

while [ $# -gt 0 ]; do
  case "$1" in
    --regen)  rm -f "$CORPUS/30_bench_300page_6mp.cbz" "$CORPUS/31_bench_600page.pdf" ;;
    --rounds) ROUNDS="$2"; shift ;;
    *) echo "bench-decode: unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done

CBZ="$CORPUS/30_bench_300page_6mp.cbz"
PDF="$CORPUS/31_bench_600page.pdf"

# The corpus is generated, never committed: 237 MB of 6 MP JPEG does not belong in a repo, and
# the lead's brief says so explicitly. Generating is pure Python plus one cjpeg call.
if [ ! -f "$CBZ" ] || [ ! -f "$PDF" ]; then
  echo "bench-decode: generating the M11 size corpus (one-off, ~1 min)..."
  python3 tools/make-corpus.py --out "$CORPUS" --include-bench
fi

for f in "$CBZ" "$PDF"; do
  if [ ! -f "$f" ]; then
    echo "bench-decode: missing $f -- the corpus generator skipped it (no JPEG encoder?)." >&2
    echo "bench-decode: install libjpeg-turbo (cjpeg) or ImageMagick, then re-run." >&2
    exit 1
  fi
done

mkdir -p "$OUT/android" "$OUT/com/absolutex/source/libarchive"

# Same two shims tools/test-archive-recovery.sh uses: a no-op android/log.h, because the
# bridge logs through __android_log_print and there is no Android here, and a Java class in
# the bridge's own package so System.loadLibrary and the native symbol names line up.
cat > "$OUT/android/log.h" <<'HEADER'
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void) priority; (void) tag; (void) format; return 0;
}
HEADER

cp "$ROOT/tools/ArchiveWalkBench.java" "$OUT/com/absolutex/source/libarchive/LibArchive.java"

echo "bench-decode: building the JNI bridge from source/libarchive/src/main/cpp/archive_jni.c"
clang -shared -fPIC -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" -I"$OUT" -I"$ARCHIVE_PREFIX/include" \
    "$ROOT/source/libarchive/src/main/cpp/archive_jni.c" \
    -L"$ARCHIVE_PREFIX/lib" -larchive -o "$OUT/libabsolutex_archive.dylib"

"$JAVA_HOME/bin/javac" -d "$OUT" "$OUT/com/absolutex/source/libarchive/LibArchive.java"

# One JVM for both books: a second JVM start would pay JIT warmup again, and the whole point
# is a comparison between stages inside one process.
"$JAVA_HOME/bin/java" --add-opens java.base/java.io=ALL-UNNAMED \
    -Djava.library.path="$OUT" -cp "$OUT" \
    com.absolutex.source.libarchive.LibArchive "$CBZ" "$PDF" "$ROUNDS"
