# Developing Absolutex

Everything about how Absolutex is built, measured and maintained: implementation status, platform
decisions, the module map, build setup, licensing detail, contributor notes and the roadmap. For
what the app is and does, see the [README](../README.md).

## Status

**The reader** opens `.cbz`, `.cbr`, `.cb7`, `.cbt` and PDF through SAF or a file path, and
renders them tiled. It has reading flows (LTR, RTL, vertical), page layouts (single, double,
double-with-cover, continuous vertical), four fit modes, pinch and double-tap zoom, a 3x3 tap
grid mirrored for RTL, immersive chrome with a seek bar, a thumbnail strip, bookmarks, a table
of contents, page export, keyboard, gamepad and volume-key control, and progress that resumes
the last book on launch.

It also has page transitions, per-book reading flow and layout overrides, an AGSL colour
pipeline with GPU crop, Mitchell/Lanczos upscaling and an automatic background colour.

**Around it:** the library is the app's home screen, with the reader and settings as
destinations. A parallel scanner with a filesystem watcher keeps it live, and there is a
thumbnail pipeline, a settings surface, and remote modules for SMB, FTP/FTPS and Komga/Kavita
progress sync. SMB and FTP/FTPS are wired into the app; Komga and Kavita are built but not offered
(see below).

**First run.** The library's top bar has an "Open file" button that launches the system file picker
(`ActivityResultContracts.OpenDocument`, types in `OpenFileTypes`, ending in `*/*` so an unknown type
can still be chosen). The picked file opens through the same reader sheet as a library row; when the
provider grants a persistable read permission it is also remembered for resume and written to the
library (`OpenedBooks`), which is what puts it in Recent. The empty state is decided from the saved
folders, not the book list: no folder (add one or open a file), scanning, folder unreadable, and
scanned with nothing found (rescan or add another). `ContentResolverTree` logs the exception class of
a failed folder read and counts it (`ReadFailures`) instead of returning an empty list silently; the
app shell publishes that through `ScanStatus`. The manifest's VIEW filter also declares the CB7, CBT,
7z, RAR and tar types, so a file manager offers Absolutex for them.

**Scan safety.** A SAF or filesystem scan that could not read any folder keeps the books it found and skips
stale-row deletion for the whole location; cancellation also skips deletion. Forgetting a location
still deliberately removes its rows. Device check: make a subfolder unreadable, rescan, and confirm
its favourites survive while the library reports that the folder could not be read.

**Built but not yet connected.** Four features are merged, tested and unreachable, which is
worth stating plainly rather than leaving for someone to discover:

- **Progress sync is inert.** `SyncController.onAppStart` and `onAppBackgrounded` fire from the
  app shell, but `onBookOpened`, `onPageSettled` and `onBookClosed` have no callers anywhere, so
  the controller never learns which book is open — and `onAppBackgrounded` takes no book id
  precisely because it expects to have been told. Nothing is pushed to or pulled from Komga or
  Kavita today, so the Add-server form does not offer them: `OFFER_SYNC_SERVER_KINDS` in
  `ServerKindOptions.kt` is `false`, and flipping it is the change that goes with wiring those
  three hooks. Servers of those kinds saved earlier still load, list, edit and delete; opening
  one shows its kind read-only.
- **Remote covers are never fetched.** `TransportCoverFetcher` and `FtpCovers` exist; no screen
  mints the `ThumbRequest` that would drive them.
- **No cloud account can be added.** `:remote:cloud` holds the PKCE flow, the token endpoint, the
  per-account token store, OneDrive over Graph and Dropbox over its v2 API, all range-reading so a
  300 MB book opens without transferring 300 MB. Nothing constructs any of it: no screen offers a
  cloud provider, and no `ComicSource` resolves to one.
- **No book can be taken offline.** `:remote:offline` copies a remote book to disk atomically,
  resumes an interrupted copy and reads the result back through `FileRangeTransport`. Nothing
  calls it, and there is no registry recording that a copy exists, so a local copy could not be
  preferred over the network even if one were made.

Those last two are a step further from reachable than the other two, and the distinction
matters when estimating the work: **no module depends on `:remote:cloud` or `:remote:offline`.**
They are in `settings.gradle.kts`, so they compile and their tests run on every PR, but they are
not on the app's dependency graph and contribute nothing to the APK. Connecting them is a build
edge plus wiring, not only a call site — and the offline registry needs storage, which means an
`AbsolutexDatabase` migration on a schema other lanes are actively changing.

### Cloud and offline are work in progress

They are **not** part of the current scope, and the rest of the project is not waiting on them.
Saying so explicitly, because fourteen merged pull requests with green CI read like a finished
feature and are not one.

What is done is the hard half and it is real: PKCE, the token endpoint, per-account token storage
with single-flight refresh, OneDrive over Microsoft Graph, Dropbox over its v2 API, pre-authenticated
link handling that re-resolves a refused URL exactly once, and atomic resumable offline copies.
All of it range-reads, so a 300 MB book opens without transferring 300 MB.

What is not done, in the order it would have to happen:

1. a build edge from `:app`, which is what first puts either module in the APK;
2. an account screen and a live OAuth redirect, which is the first point the flow leaves fakes;
3. an offline registry — storage, so a database migration — plus a copy trigger and progress UI;
4. dispatch, so opening a cloud book resolves to a `ComicSource`.

**Step 2 cannot be completed by anyone working in this repository.** It needs three client
registrations that only the project owner can create: an Azure application id for OneDrive, a
Dropbox app key, and a Google OAuth client with the release signing SHA-1 for Drive. Until those
exist the lane can be built and tested against fakes and no further, which is exactly where it is.

Folder browsing over a remote share is in review (#50), and the settings row that finally opens
the servers list is #98. The roadmap names the pull request for each. See [Roadmap](#roadmap).

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
static, `-Os -fvisibility=hidden`; the resulting `.so` has libarchive inside and only the JNI
entry points exported.

**Archive formats and native flags.** libarchive is configured with zlib and liblzma on and
everything else off: no bzip2, zstd, lz4, OpenSSL, libxml2, expat, iconv, ACL or xattr, and none
of its command-line tools. That gives ZIP (deflate, and ZipCrypto decryption), RAR4, RAR5, 7z with
LZMA, LZMA2 and the BCJ/ARM64/Delta filters (what an ordinary `.cb7` uses), and TAR.
7z with PPMd or Deflate also works because libarchive carries those itself; **7z with BZip2 or
Zstandard does not**, since their libraries are off. Encrypted 7z (7-Zip AES) cannot be decrypted at
all; see "Solid and encrypted 7z" below.

**liblzma** comes from the xz release tarball, a second `FetchContent` pinned by URL and SHA256
(xz 5.8.4), built as a static, **decoder-only** library: no encoders, no threaded decoder, no `xz`
or `xzdec`, no scripts, translations, docs or tests. libarchive's own `find_package(LibLZMA)` is
pointed at that target through `LIBLZMA_INCLUDE_DIR` / `LIBLZMA_LIBRARY`, with its three probe
results pre-answered, because a probe cannot link against a library that does not exist yet at
configure time. Never pin xz 5.6.0 or 5.6.1, which shipped the backdoor. To bump xz: take a
release whose `.sig` verifies against Lasse Collin's key (fingerprint
`3690 C240 CE51 B467 0D30 AD1C 38EE 757D 6918 4620`, from tukaani.org), copy the hash of the
`.tar.gz` asset, and cross-check it against a second source (the GitHub asset digest, Homebrew's
`xz` formula) before changing the pin.

### Solid and encrypted 7z

**Solid 7z is expensive to page through.** 7-Zip's default is one solid LZMA2 block, and a solid
block can only be decoded from its start, so page *N* costs decoding pages 1…*N* on every read:
`nativeExtract` is stateless and reopens the archive per call, and libarchive cannot skip an entry
inside a solid block without decoding it. Measured on the host (Apple M4, release build) on a
300-page solid archive of 1 MB pages that barely compress: page 0 in 32 ms, page 50 in 1.2 s,
page 150 in 3.5 s, page 299 in 6.9 s; a 10-page window near the end also costs ~6.9 s, but the
same ten pages read one call each cost ~68 s. A non-solid archive is flat, ~23 ms per page
wherever the page is. A phone will be slower than that host. As the code reads, every page turn
starts at least one walk from the start of the block (the prefetch window is one walk per turn,
not per book), so reading forward through a big solid book repeats work quadratically, and a jump
to the end waits for the whole block. Related: 7-Zip sorts a solid block's
files by extension, so `ComicInfo.xml` lands **after** the pages, and reading the sidecar at open
decodes the entire block before page 1 appears (the same ~7 s on the 300 MB host case). The per-entry
128 MB and 20,000-entry caps in `archive_jni.c` still apply per entry, but nothing bounds the total
decoded while *skipping* entries in a solid block, so a crafted solid 7z can keep one call busy for
a long time. liblzma also allocates the LZMA dictionary size the archive declares (7-Zip's own
`-mx=9` uses 64 MB; the format allows far more), with no limit set by libarchive's 7z reader,
so a hostile archive can ask for a large allocation per concurrent decode; it is only touched as
data is actually decoded.

**The reader decodes a solid 7z once.** `LibArchiveSource.open(passphrase, cache, openFd)` probes the
archive (`nativeProbeSolid`: two entries of one solid block start reading at the same offset of the
file, which libarchive's public API cannot say any other way) and, for a solid 7z of 32 MiB or more,
starts ONE background pass (`nativeStreamEntries`, one forward decode) that writes each page to
`<cacheDir>/solid-archives/<hash of uri+size+mtime>/NNNNN.bin` as it arrives, with a `complete`
marker written last. A page turn reads its file, or waits (interruptibly, so a cancelled jump
frees its thread) for the pass to reach it; a finished cache is reused by the next open with no
decoding. `ComicInfo.xml` is parsed when the pass meets it instead of at open. The cache holds at
most min(1 GiB, a quarter of free space plus its own size) across archives, evicts least recently
used first, never evicts an open book, and is skipped (today's direct reads) for a book over the cap,
a full disk, a failed write, a second open of the same file, and encrypted archives (decrypted pages
never touch the disk). Only the reader opts in (`openBook(cacheSolid = true)`); covers and scans do
not. **Not done:** solid RAR/CBR has the same cost, but libarchive exposes no solid flag for it
(rar5.c keeps it in a private struct), so it still reads directly; the pass itself is format
agnostic and only the probe is missing. `tools/test-archive-solid.sh` proves the native half,
`tools/bench-solid.sh` measures before and after, and `SolidCacheTest` covers the cache logic.

**Encrypted 7z** is reported as unsupported encryption, never as "no readable pages". With the
header encrypted, libarchive fails at open with "encrypted, but currently not supported"; with only
the content encrypted it lists the entries, flags them encrypted, and fails on the first read with
the same text. `archive_jni.c` maps both to `UnsupportedEncryptionException`, and for a 7z it does
so *instead of* `PasswordRequiredException`, because no password can help: the reader shows its
"encryption Absolutex can't open" message rather than a password prompt that ends in the same
place. `tools/test-archive-7z.sh` and `SevenZipSourceTest` cover both variants.

`:source:pdf` is the one exception to "nothing is vendored, everything is built from source",
and it is not by choice: PDFium ships no release tarball and no standalone CMake build, only a
`gclient` solution that pulls Chromium's build tree and builds with GN. It is therefore a
**prebuilt shared library**, pinned by URL and SHA256 exactly like libarchive's source tarball,
and it is the only dependency whose bytes we do not compile ourselves. `tools/refresh-pdfium.sh`
re-resolves the hash, the licence texts and a copyleft scan together, so a version bump cannot
quietly change any of them.

## Licensing

Absolutex itself is [Apache-2.0](../LICENSE). That choice follows its dependencies —
AndroidX, Kotlin and Compose are all Apache-2.0 — and it carries an explicit patent
grant, which matters for a reader that links native decoders.

No dependency is GPL or AGPL. No ads, no analytics, no crash reporting.

| Component | Licence | Note |
|---|---|---|
| **libarchive 3.8.9** | New BSD | RAR4/RAR5 readers are clean-room |
| **liblzma (xz 5.8.4)** | 0BSD | Public-domain-equivalent: no attribution required. Decoder-only, built from the pinned release tarball; see [NDK](#ndk) |
| **PDFium 155.0.8044.0** | BSD-3-Clause | Bundled deps all permissive; see [`source/pdf/LICENSES.md`](../source/pdf/LICENSES.md) |
| AGP, Gradle, Kotlin, Compose, Hilt, Room, DataStore | Apache-2.0 | |

### On RAR

Absolutex reads RAR through **libarchive**, never through RARLAB's UnRAR source.

This matters. The UnRAR licence forbids using its code to re-create the RAR compression
algorithm and carries redistribution restrictions that are awkward for an app store build.
libarchive's RAR4 and RAR5 readers are independent implementations under New BSD, so none of
those terms apply and nothing GPL enters the tree. `junrar` is also excluded: it is
UnRAR-derived.

## Notes for contributors

### Who works here, and how commits are attributed

Absolutex is built by AI coding agents working in parallel lanes, one branch and pull request
per change. The lead reviews every pull request, runs it on the reference phone, and is the
only one who merges or installs builds on the device.

| Agent | Tool | Lanes |
|---|---|---|
| Lead | Claude Code | Review and merge, on-device testing, the Material 3 redesign, the text EPUB reader |
| Agent 1 | Hermes | Formats (container detection, folders, comic EPUB), CBZ export, decode prefetch |
| Agent 2 | OpenCode | Network shares and remote browsing, PDF open and scroll performance, cover transition |
| Agent 3 | Factory Droid | Library, settings and design |
| Agent 4 | Claude Code (cloud) | Native hardening, archive recovery and encryption |
| Agent 5 | Claude Code (cloud session) | Cloud sources (OneDrive, Dropbox, Google Drive), offline copies |
| Agent 6 | Claude Code (cloud) | Internationalisation, accessibility, reading insights |
| Agent 7 | Cline (desktop) | Review and small fixes |

**Attribution.** Commits carry the repository identity. An agent whose tool adds a
`Co-authored-by:` trailer keeps it, so the tool that wrote a change is visible on the commit
itself:

| Tool | Trailer |
|---|---|
| Claude Code | `Claude <model> <noreply@anthropic.com>`, naming the model, for example `Claude Opus 5.5` |
| Factory Droid | `factory-droid[bot] <138933559+factory-droid[bot]@users.noreply.github.com>` |
| Hermes, OpenCode, Cline | none; the pull request names the agent |

Pull request descriptions end with the generating tool's `🤖 Generated with ...` line. When
you contribute, follow the same convention.

**Rules every agent follows.** Branch from `main` and open a pull request; never push to
`main` or force-push someone else's branch. Only the lead installs on the phone. Schema
changes to the Room database are agreed with the lead first. A pull request is green only
when `detekt`, the unit tests of the touched modules, `tools/check-strings.py` and
`tools/check-apk-size.py` pass, with their output shown.

### Reading-data backup

Settings → Backup exports/imports schema-v1 JSON through SAF (no permissions). The file includes
book names: progress, recent page-view history, bookmarks, favourites, per-book overrides and
app/reader/rendering preferences. It excludes server records, credentials, location grants and
`onboarded` (onboarding stays local). Text-EPUB within-chapter fraction, text scroll/page choice
and tap-guide-seen are excluded; a restored text EPUB opens at its chapter start.
Identity is the exact displayName + size string: rename, resize, case or Unicode-normalisation
changes prevent matching. Identities are opaque data, never opened as paths; URI fallbacks are skipped.

Import validates the entire file before writes: strict UTF-8/JSON (optional initial BOM), 4 MiB,
10,000 book identities, 10,000 history and bookmark entries each, 1,024 characters per string,
nesting depth 16 and 100,000 JSON values, including unknown fields. Numeric text is capped at
64 characters and scale at ±128. Future schema versions are refused; unknown fields are ignored
within those limits. Any progress/history/bookmark timestamp later than now + one day clamps to now.

Export reads bounded batches (up to 10,000 per category and the newest 5,000 history rows).
Rows are validated, then actual UTF-8 JSON bytes are measured against the writer's 4 MiB guard.
If byte/value/identity caps are exceeded, at most 75 attempts halve the oldest remaining tier:
history first, then bookmarks, favourites, per-book overrides and finally progress only after
all other tiers are empty. Lists use descending timestamps and identity ties; favourites use
last-read/added time for library rows and arrival time for pending entries, because the schema
has no favourite-created timestamp. Overrides use last-read time. Older history and all other
invalid/dropped items are counted exactly; source rows are not removed. Non-conforming identities,
positions and timestamps are skipped, never exported as SAF URIs. A provider failure during the
truncating write can leave a partial file; the error asks the user to export again before using it.

Newer progress wins; newer last-read time replaces the WHOLE per-book preference row, so it can
clear a local override. Ties preserve local records. History, bookmarks and favourites are unions:
items removed after making the backup can return. Omitted preference keys preserve existing values;
PrefCodec supplies defaults/bounds. Results count changed books, written progress and favourites
actually applied or newly pending; an identical re-import reports nothing new.

Reading data merges in one Room transaction, then settings/pending favourites in one DataStore
edit. There is no cross-store atomicity: a failed second phase reports which reading changes
committed and asks for re-import, which retries the rest safely. Only unmatched favourites consume
the 2,000-entry pending cap (identities at most 1,024 characters, bounded arrival-time metadata).
When full, the oldest pending entries are dropped and counted; existing entries keep their age
on re-import. LibraryRepository applies/clears pending entries after matching scans. Re-add folders
after reinstall; grants cannot be restored. Forgetting a folder still drops favourites on its
library rows, a pre-existing limitation for a separate favourites-table migration. No migration
is introduced here. SAF/provider failures, rotation, TalkBack and actual reader restoration need
device checks; CI covers JVM data/merge/hostile-input tests.

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
| Page turn < 8.3 ms | CPU frame time P50 2.8 · P90 3.8 · P95 4.2 · P99 5.2 ms (5 iterations, 93–166 frames each) | **met**, last measured before the reader gained layouts, strip and chrome |
| Zero dropped frames | 88 turns, 1,387 frames: **2 missed deadlines (0.14%)**, 0 missed vsync, 0 slow UI-thread frames | **not met** |
| Cold start < 300 ms | Time to initial display, partial compilation: median 281.4 · min 256.9 · max 308.4 ms on an idle phone; median 317.3 ms measured again minutes later in the same session (no compilation: median 382.2; full AOT: median 351.6) | **met at the median on an idle phone**, not at the tail, and not while the device is warm |
| Pinch zoom, no drops | Sustained pinch open/close: frame time P50 3.3 · P90 4.8 · P95 5.6 · P99 6.5 ms, overrun P99 −0.4 ms (230–256 frames per iteration) | **met** |
| Steady reader memory under the §3 ceiling | 150 MB PSS / 304 MB RSS after 12 page turns, against ~1.06 GiB (15% of this phone's 7.4 GB) | **met** |
| Tap → first page < 250 ms | Warm, time to full display (base layer decoded): median 329.8 · min 304.5 · max 443.2 ms; window up at 93 ms | **not met** (was 513.6, then 291.6 before the library and navigation joined the startup path) |

What the two dropped frames are, from a Perfetto trace: not the app's drawing (RenderThread
draw commands stay under 2.5 ms). RenderThread blocks ~24 ms in `eglSwapBuffers → queueBuffer`
waiting for SurfaceFlinger to release a buffer, and framestats show GPU completion of 22–28 ms on
exactly those frames. The reader layer composites as `DEVICE` even in Display P3, so wide-gamut
colour mode is not forcing GPU composition. Leading suspect: GPU frequency dropping during the
pauses between swipes. Open.

The first cold-start numbers (median 333.9 ms) were measured with R8 off in the benchmark variant: opening the
unshrunk dex alone cost 38 ms of `bindApplication`. The variant now inherits release's shrinking, which also
means the minified app — the one users get — runs on a device every time a benchmark runs.

PSS understates the reader's graphics: hardware bitmaps are dmabuf-backed and only partly attributed to the
process, which is why RSS is the larger number above.

Tap → first page went 513.6 → 373.3 → 312.9 → 291.6 ms at the median. Neighbours stopped decoding alongside the
page being opened; the opened page stopped being decoded twice (base layers are shared, one decode per page and
size); and the activity starts opening a launch Uri in `onCreate` instead of after the splash and first layout.
What remains is ~48 ms of RAR extraction and one ~174 ms decode of a 6 MP JPEG to screen size. Two ideas were
measured and dropped: a half-resolution preview decode was barely cheaper on this decoder, and strip-parallel decode
cannot scale for baseline JPEGs, whose entropy stream must be read from the top for any region.

A caution about all of the above: on this phone the same build measured 281 ms of cold start right
after a reboot and 353–378 ms an hour into a working session, with nothing changed but the device.
Numbers taken while the phone is warm are not comparable to numbers taken cold, and a regression
should be confirmed against a fresh reboot before it is believed — which is how the "regression"
the library and navigation appeared to cause turned out to be the phone, not the code.

The frame-time budgets (page turn, zoom, pinch) could not be re-measured today: injected input
from the instrumentation is being dropped again, which is the *Disable permission monitoring*
trap below. `adb shell input` still works, so the reader itself was verified by hand.

Two traps that made every earlier number wrong, both now guarded in the benchmark:
`adb`-created `Android/data` directories are `2770 shell:ext_data_rw` (the app gets EACCES and
the benchmark timed the error screen), and OxygenOS drops injected input unless *Disable
permission monitoring* is on **and the phone has been rebooted since**.

## Roadmap

| Phase | Scope | State |
|---|---|---|
| Audit, licensing gates, platform decisions | done |
| Scaffold + CBZ/CBR vertical slice | done; page turn measured (see above) |
| Tiled renderer depth, prefetch engine, AGSL colour, GPU crop | tiles, colour (#22), upscaling (#24), crop (#25) and auto background (#26) done; prefetch engine in review (#77). Crop has a Settings switch and a reader-options chip (`RenderingPrefs.cropEnabled`, default on); PDFs stay uncropped |
| Library: parallel scanner, metadata, home, browse, search | done, and the launch destination; live updates merged (#63) |
| Reader depth: layouts, flows, transitions, bookmarks, TOC, input devices | done, transitions and per-book overrides included |
| Formats: 7z, TAR, PDFium, image folders, full codec set | archives, PDF, image folders (#76) and recovery/encryption/indexed extraction (#39) all open in the reader; `:source:epub` (#90) is built but **not yet dispatched** by `openBook` |
| Settings surface | done and reachable from the library |
| Remote: SMB streaming, FTP, Komga/Kavita sync | transports merged and reachable — the settings row that opens the servers list is #98. Sync is merged but **inert**, and the Add-server form hides Komga and Kavita for that reason (`OFFER_SYNC_SERVER_KINDS`): `SyncController.onAppStart`/`onAppBackgrounded` fire, but `onBookOpened`/`onPageSettled`/`onBookClosed` have no callers, so the controller never learns a book is open and nothing is pushed or pulled. Browse UI in review (#50). See the `TODO(lead)`s in `SyncController.kt` |
| NPU upscaling R&D (§4 M6); release polish: licence, signing, launcher icon, baseline profiles | R&D done and the verdict is **no** — see [`docs/npu-sr-report.md`](npu-sr-report.md); Apache-2.0 (#70), signing config, licences screen and launcher icon (#56) done |
| — | Cloud sources (OneDrive, Dropbox, Drive) and offline copies | **work in progress, out of current scope.** Engine merged and tested; not on the app's dependency graph, no UI, and the OAuth client registrations are the project owner's to create. See [Cloud and offline are work in progress](#cloud-and-offline-are-work-in-progress) |
