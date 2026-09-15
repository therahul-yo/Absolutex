# Absolutex

A comic and manga reader built exclusively for modern flagship Android hardware.

Absolutex deliberately does not support low-end devices. There are no 16-bit colour paths, no
small-cache fallbacks, no single-threaded decode kept around for weak SoCs. The hardware floor
is a feature: every compromise removed is a code path that cannot rot or drop a frame.

## Status

Phase 2 (scaffold + vertical slice) is working end to end on device: open a `.cbr`/`.cbz`
through SAF, tiled render, single-page LTR, pinch/zoom, progress persisted, last book resumed
on launch.

**Not yet built:** reader chrome, thumbnail strip, TOC, AGSL colour pipeline, library scanner,
settings surface, remote sources, PDF. See [Roadmap](#roadmap).

## Platform floor

| Item | Value |
|---|---|
| minSdk | **33** (Android 13) |
| targetSdk | 36 |
| compileSdk | 37 |
| ABI | **arm64-v8a only** |
| Reference device | OnePlus 11R — SM8475 (Snapdragon 8+ Gen 1), Adreno 730, 7.06 GiB RAM, 1240×2772 @ 120 Hz, Display-P3, HDR10+ |

### Why minSdk 33, not 31

`RuntimeShader` (AGSL) has a hard API 33 floor, and GPU colour correction is the architecture
we want rather than a CPU fallback. Android 12 is roughly 11% of Android globally, but that
share lives almost entirely on hardware already excluded by the floor above: every qualifying
SoC shipped with Android 13 or later. The install-base cost inside the supported hardware class
is approximately zero, and it buys a single render path.

### Why targetSdk 36, not 37

API 37 removes the developer opt-out for orientation and resizability on displays wider than
600dp — `setRequestedOrientation` and `android:screenOrientation` are ignored there. The reader
offers a rotation lock, which would silently stop working on tablets and unfolded foldables.
Play requires 36 for new apps today; 37 becomes mandatory in **August 2027**. Tracked as debt:
the migration to genuinely adaptive layouts happens deliberately before that date.

## Module map

```
:app                    Application, Hilt root, SAF picker, resume-last-book
:core:model             pure Kotlin — Book, Page, ReadingFlow, FitMode          (JVM)
:core:ui                Material 3 Expressive theme, dynamic colour, true black
:core:data              Room progress, DataStore prefs
:core:decode            dispatchers, tile geometry, tile cache, page decoding
:source:api             ComicSource, natural sort, entry filtering              (JVM)
:source:libarchive      libarchive over JNI — cbz / cbr / cb7 / cbt
:source:pdf             PDFium over JNI — per-tile render, page size, outline
:feature:reader         the reader surface
:benchmark              Macrobenchmark
```

`:core:model` and `:source:api` are plain Kotlin/JVM on purpose: the logic most likely to carry
bugs — natural sort, filename parsing, entry filtering — then unit-tests on the JVM in
milliseconds with no emulator.

### Data flow

```
SAF Uri
  └─ ContentResolver.openFileDescriptor  (a FRESH fd per read — see note below)
       └─ :source:libarchive  nativeList / nativeExtract   (JNI → libarchive)
            └─ :core:decode   PageImage
                 ├─ ImageDecoder.setTargetSize → base layer (hardware bitmap)
                 └─ BitmapRegionDecoder        → tiles, cached by (page, col, row, sample)
                      └─ :feature:reader  PageCanvas → Compose draw phase
```

## Build

Requires **JDK 21** and the Android SDK with NDK `28.2.13676358` and CMake `4.1.2`.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
./gradlew test                                   # JVM unit tests
./gradlew :source:libarchive:connectedDebugAndroidTest   # needs a device
```

### NDK

`:source:libarchive` builds libarchive from source via CMake `FetchContent`, pinned to a
release tag **and** its SHA256. Nothing is vendored into the tree. The build is arm64-only,
static, `-Os -fvisibility=hidden`; the resulting `.so` is about 350 KB with libarchive inside
and only the two JNI entry points exported.

`:source:pdf` is the one exception to "nothing is vendored, everything is built from source",
and it is not by choice: PDFium ships no release tarball and no standalone CMake build, only a
`gclient` solution that pulls Chromium's build tree and builds with GN. It is therefore a
**prebuilt shared library**, pinned by URL and SHA256 exactly like libarchive's source tarball,
and it is the only dependency whose bytes we do not compile ourselves. `tools/refresh-pdfium.sh`
re-resolves the hash, the licence texts and a copyleft scan together, so a version bump cannot
quietly change any of them.

## Licensing

No dependency is GPL or AGPL. No ads, no analytics, no crash reporting.

| Component | Licence | Note |
|---|---|---|
| **libarchive 3.8.9** | New BSD | RAR4/RAR5 readers are clean-room |
| **PDFium 155.0.8044.0** | BSD-3-Clause | Bundled deps all permissive; see [`source/pdf/LICENSES.md`](source/pdf/LICENSES.md) |
| AGP, Gradle, Kotlin, Compose, Hilt, Room, DataStore | Apache-2.0 | |

### On RAR

Absolutex reads RAR through **libarchive**, never through RARLAB's UnRAR source.

This matters. The UnRAR licence forbids using its code to re-create the RAR compression
algorithm and carries redistribution restrictions that are awkward for an app store build.
libarchive's RAR4 and RAR5 readers are independent implementations under New BSD, so none of
those terms apply and nothing GPL enters the tree. `junrar` is also excluded: it is
UnRAR-derived.

## Notes for contributors

### Never `dup()` a file descriptor to share it across threads

This cost two debugging cycles, in two different disguises.

`dup()` returns a descriptor that **shares its file offset** with the original. Two threads
reading pages concurrently then move each other's position and silently corrupt each other's
reads — libarchive reports entries as unreadable rather than failing cleanly.

Re-opening `/proc/self/fd/N` yields an independent open file description and fixes this for a
real file path. It does **not** work for a SAF descriptor: SAF exists precisely to grant access
the app does not have by path, so the re-open re-checks permission and fails with `EACCES`,
falling back to `dup()` and the same corruption.

The rule: anything reading an archive concurrently must obtain a **fresh descriptor per read**.
`LibArchiveSource` therefore takes a factory, not a descriptor.

`:source:pdf` is deliberately *not* written that way, and should not be "fixed" to match. It
holds one descriptor for the life of the document because every read there is a `pread()`,
which takes its offset as an argument and never consults the shared one. Opening per read would
re-parse the PDF xref table and rebuild its object map on every tile. If you ever add a plain
`read()` to that file, make it a `pread()` — do not re-open the document.

### Frame-budget rules in the reader

The page-turn budget is 8.3 ms at 120 Hz. Three things protect it, and all three are easy to
undo by accident:

1. **Pan/zoom state is read only inside the `Canvas` draw lambda.** Reading it in a composable
   body instead recomposes the subtree on every gesture frame.
2. **`Rect` and `Paint` are hoisted and mutated in place.** No allocation per tile per frame.
3. **Tiles draw via `nativeCanvas.drawBitmap`**, which accepts hardware bitmaps with no
   readback — which is what keeps colour correction available as a draw-time AGSL shader
   instead of a pixel edit.

Do not reach for `detectTransformGestures` in the reader. It consumes every drag past touch
slop, which stops `HorizontalPager` ever seeing a swipe.

### Measured on the reference device

OnePlus 11R (Snapdragon 8+ Gen 1, Android 16), display confirmed at 120 Hz, benchmark build,
*Absolute Batman 001* (CBR, 45 × 1988×3057 JPEG). Run with `tools/run-benchmark.sh`.

| Budget (§3) | Measured | Verdict |
|---|---|---|
| Page turn < 8.3 ms | CPU frame time P50 2.8 · P90 3.8 · P95 4.2 · P99 5.2 ms (5 iterations, 93–166 frames each) | **met** |
| Zero dropped frames | 88 turns, 1,387 frames: **2 missed deadlines (0.14%)**, 0 missed vsync, 0 slow UI-thread frames | **not met** |
| Cold start < 300 ms | Time to initial display, baseline profile: median 286.9 · min 256.8 · max 340.4 ms (no compilation: median 299.9; full AOT: median 302.4) | **met at the median**, not at the tail |
| Pinch zoom, no drops | Sustained pinch open/close: frame time P50 3.3 · P90 4.8 · P95 5.6 · P99 6.5 ms, overrun P99 −0.4 ms (230–256 frames per iteration) | **met** |
| Tap → first page, steady memory | — | not yet measured |

What the two dropped frames are, from a Perfetto trace: not the app's drawing (RenderThread
draw commands stay under 2.5 ms). RenderThread blocks ~24 ms in `eglSwapBuffers → queueBuffer`
waiting for SurfaceFlinger to release a buffer, and framestats show GPU completion of 22–28 ms on
exactly those frames. The reader layer composites as `DEVICE` even in Display P3, so wide-gamut
colour mode is not forcing GPU composition. Leading suspect: GPU frequency dropping during the
pauses between swipes. Open.

The first cold-start numbers (median 333.9 ms) were measured with R8 off in the benchmark variant: opening the
unshrunk dex alone cost 38 ms of `bindApplication`. The variant now inherits release's shrinking, which also
means the minified app — the one users get — runs on a device every time a benchmark runs.

Two traps that made every earlier number wrong, both now guarded in the benchmark:
`adb`-created `Android/data` directories are `2770 shell:ext_data_rw` (the app gets EACCES and
the benchmark timed the error screen), and OxygenOS drops injected input unless *Disable
permission monitoring* is on **and the phone has been rebooted since**.

## Roadmap

| Phase | Scope | State |
|---|---|---|
| 1 | Audit, licensing gates, platform decisions | done |
| 2 | Scaffold + CBZ/CBR vertical slice | working; page turn measured (see above) |
| 3 | Tiled renderer depth, prefetch engine, AGSL colour, GPU crop | next |
| 4 | Library: parallel scanner, metadata, home, browse, search | |
| 5 | Reader depth: layouts, flows, transitions, bookmarks, TOC, input devices | |
| 6 | Formats: 7z, TAR, PDFium, image folders, full codec set | |
| 7 | Settings surface | |
| 8 | Remote: SMB streaming, FTP, Komga/Kavita sync | |
| 9 | NPU upscaling R&D, release polish | |
