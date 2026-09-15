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

# Phase 4: fail fast with one device exactly — two devices make every adb call ambiguous.
if [ "$(adb devices | grep -c -w device)" -ne 1 ]; then
  echo "expected exactly 1 adb device; got:" >&2
  adb devices >&2
  exit 1
fi

adb install -r "$(find app/build/outputs/apk/benchmark -name '*.apk' | head -1)"
adb install -r "$(find benchmark/build/outputs/apk/benchmark -name '*.apk' | head -1)"

# Stage the corpus in the app's OWN external dir: readable with no permission, and the only
# place that works under scoped storage without MANAGE_EXTERNAL_STORAGE.
adb shell "mkdir -p /sdcard/Android/data/$PKG/files"
adb push "$CORPUS_SRC/$BOOK" "/sdcard/Android/data/$PKG/files/absolute-batman-001.cbr"
adb shell "chmod 644 /sdcard/Android/data/$PKG/files/"'*.cbr'

adb shell am instrument -w -r \
  -e class "$FILTER" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$LOG"

echo "--- metrics ---"
grep -E "frameDurationCpuMs|frameOverrunMs" "$LOG" || echo "(none captured - check $LOG)"
