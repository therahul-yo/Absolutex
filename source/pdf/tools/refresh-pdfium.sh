#!/usr/bin/env bash
# Re-pins the PDFium prebuilt used by :source:pdf.
#
# Usage: tools/refresh-pdfium.sh [release-tag]     e.g. tools/refresh-pdfium.sh chromium/8044
#
# Prints the SHA256 to paste into src/main/cpp/CMakeLists.txt, refreshes the vendored
# licence texts, and re-runs the GPL scan. Never bump the pin without running this: the hash
# is the only supply-chain check we have on a dependency we do not compile ourselves.
set -euo pipefail

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    echo "usage: $0 <release-tag>   (e.g. chromium/8044)" >&2
    exit 2
fi

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$MODULE_DIR/src/main/assets/licenses/pdfium"
URL="https://github.com/bblanchon/pdfium-binaries/releases/download/$TAG/pdfium-android-arm64.tgz"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Fetching $URL"
curl -fsSL "$URL" -o "$WORK/pdfium.tgz"
tar xzf "$WORK/pdfium.tgz" -C "$WORK"

SHA="$(sha256sum "$WORK/pdfium.tgz" | cut -d' ' -f1)"

echo
echo "=== Paste into src/main/cpp/CMakeLists.txt ==="
echo "    URL      $URL"
echo "    URL_HASH SHA256=$SHA"
echo
echo "=== VERSION ==="
cat "$WORK/VERSION"

echo
echo "=== Build configuration (v8 and xfa must both be false) ==="
grep -E 'pdf_enable_v8|pdf_enable_xfa|target_cpu|target_os' "$WORK/args.gn"

echo
echo "=== Refreshing vendored licence texts ==="
rm -f "$ASSETS"/*
cp "$WORK"/licenses/* "$ASSETS/"
cp "$WORK/LICENSE" "$ASSETS/pdfium-binaries-packaging.txt"
ls "$ASSETS"

echo
echo "=== 16 KB page alignment (every LOAD segment must be 0x4000) ==="
# Deliberately AFTER the licence refresh, and deliberately non-fatal.
# macOS has no readelf. Under `set -euo pipefail` an earlier placement meant every developer
# on this project — all of whom are on macOS — got the new URL_HASH printed and then watched
# the script die, leaving the vendored licence texts describing the PREVIOUS binary. Paste the
# hash, ship stale licences. The NDK provides llvm-readelf; Homebrew binutils provides greadelf.
READELF=""
for candidate in readelf llvm-readelf greadelf \
                 "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf" \
                 "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"; do
    if [[ -n "$candidate" ]] && command -v "$candidate" >/dev/null 2>&1; then
        READELF="$candidate"; break
    fi
done
if [[ -n "$READELF" ]]; then
    "$READELF" -lW "$WORK/lib/libpdfium.so" | awk '/LOAD/ {print "    align=" $NF}'
else
    echo "    SKIPPED: no readelf found (tried readelf, llvm-readelf, greadelf, \$ANDROID_NDK_HOME)."
    echo "    Check by hand before shipping — 16 KB alignment is required on Android 15+:"
    echo "      \$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-readelf -lW <libpdfium.so>"
fi

echo
echo "=== Copyleft scan ==="
# Expected hits and why they are benign are documented in LICENSES.md. Anything NOT in that
# list is a new finding and must be resolved before the pin lands.
if grep -ril -E 'GPL|GNU General Public|Lesser General|Affero' "$ASSETS" > "$WORK/hits" 2>/dev/null; then
    echo "files mentioning copyleft (check each against LICENSES.md):"
    sed 's|^|    |' "$WORK/hits"
else
    echo "    no copyleft mentions at all"
fi

echo
echo "Done. Update the pinned tag and hash in src/main/cpp/CMakeLists.txt, then re-read LICENSES.md."
