#!/usr/bin/env bash
# Runs the reader macrobenchmarks WITHOUT Gradle's connectedAndroidTest.
#
# Why not Gradle: connectedAndroidTest uninstalls the target APK after every run, which takes
# the staged corpus, the DataStore entry and any persisted SAF grant with it. Every run then
# finds the picker instead of a book. Driving am instrument directly keeps the app installed
# and the corpus in place.
#
# Usage: tools/run-benchmark.sh [fully.qualified.Class#method]
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.absolutex
TEST_PKG=com.absolutex.benchmark
CORPUS_SRC="${CORPUS_SRC:-$HOME/Downloads}"
BOOK="Absolute Batman 001 (2024) (Webrip) (The Last Kryptonian-DCP).cbr"
FILTER="${1:-com.absolutex.benchmark.ReaderBenchmark}"
LOG=/tmp/absolutex-bench.log

./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark

# Exactly one device. None means failing halfway through, after two expensive APK builds,
# inside adb install; two makes every adb call ambiguous.
if [ "$(adb devices | grep -c -w device)" -ne 1 ]; then
  echo "run-benchmark: expected exactly 1 adb device; got:" >&2
  adb devices >&2
  exit 1
fi

# The corpus book must exist before we spend time installing APKs.
if [ ! -f "$CORPUS_SRC/$BOOK" ]; then
  echo "run-benchmark: corpus book not found: $CORPUS_SRC/$BOOK" >&2
  exit 1
fi

adb install -r "$(find app/build/outputs/apk/benchmark -name '*.apk' | head -1)"
adb install -r "$(find benchmark/build/outputs/apk/benchmark -name '*.apk' | head -1)"

# Stage the corpus in the app's OWN external dir: readable with no permission, and the only
# place that works under scoped storage without MANAGE_EXTERNAL_STORAGE.
#
# The chmod IS required: adb push lands the file owned by shell with mode 0660, and the app
# is a different uid, so it gets EACCES without it. That was observed on the reference device.
# 644 rather than 666: the app only needs to read.
adb shell "mkdir -p /sdcard/Android/data/$PKG/files"
# adb creates these directories as shell:ext_data_rw with mode 2770, and the app's uid is not in
# ext_data_rw — so the app cannot even traverse its OWN external directory and every open fails
# with EACCES. That is not hypothetical: it made every benchmark run to date time the reader's
# "Couldn't open this book" screen instead of a page. Grant traverse to other on both levels.
adb shell "chmod 755 /sdcard/Android/data/$PKG /sdcard/Android/data/$PKG/files"
adb push "$CORPUS_SRC/$BOOK" "/sdcard/Android/data/$PKG/files/absolute-batman-001.cbr"
adb shell "chmod 644 /sdcard/Android/data/$PKG/files/"'*.cbr'

adb shell am instrument -w -r \
  -e class "$FILTER" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$LOG"

echo "--- metrics ---"
grep -E "frameDurationCpuMs|frameOverrunMs" "$LOG" || echo "(none captured - check $LOG)"
# am instrument exits 0 on test failure, and tee would mask it anyway: fail on the log instead.
! grep -q "FAILURES!!!" "$LOG"
