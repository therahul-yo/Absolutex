# `tools/`

| Script | What it does |
|---|---|
| `make-corpus.py` | Generates the §8 hostile test corpus. Below. |
| `check-apk-size.py` | Breaks the release APK down by category and fails on a size regression. Run by CI; see `.github/workflows/README.md`. |
| `check-startup-budget.py` | Enforces the §3 300 ms P90 cold-start budget against Macrobenchmark JSON. Macrobenchmark has no assertion API, so the gate lives here. |
| `run-benchmark.sh` | Drives the Macrobenchmarks through `am instrument`, keeping the app installed so the staged corpus survives between runs. |

## `make-corpus.py` — the hostile test corpus (spec §8)

Generates every §8 malformation into a directory of your choosing. Nothing is committed as
a binary: the corpus is reproduced on demand, so the repository stays small and no
third-party sample lands here under an uncleared licence.

```sh
python3 tools/make-corpus.py --out build/corpus            # the fast cases (~3 s, ~60 KB)
python3 tools/make-corpus.py --out build/corpus --include-huge   # adds the ~2.25 GiB CBZ
python3 tools/make-corpus.py --out build/corpus --check     # verify reproducibility
```

Stdlib only — no Pillow, no py7zr, no PDF library. That is deliberate: every one of those
stamps its own version or a timestamp into the output, which would make the corpus
irreproducible and the `--check` gate worthless.

### What it emits

| File | What it attacks |
|---|---|
| `01_basic_ltr.cbz` | Baseline. 12 pages, `ComicInfo.xml` present. |
| `02_no_comicinfo.cbz` | No metadata sidecar — page order must fall back to natural sort. |
| `03_upper.CBZ`, `04_mixed.CbZ`, `05_upper.CB7` | Mixed-case extensions. Matching must be case-insensitive. |
| `06_zip_named_cbr.cbr` | A ZIP wearing a `.cbr` suffix. Content sniffing must beat the filename. |
| `07_deep_nesting.cbz` | Pages eight directories deep, split across two sibling chapters. |
| `08_nonascii_rtl.cbz` | Japanese, Arabic, Hebrew, combining marks, non-BMP emoji, and a U+202E bidi override. |
| `09_two_pages.cbz` | Exactly two pages — the degenerate case for any prefetch window wider than one. |
| `10_junk_entries.cbz` | `__MACOSX/._*`, `Thumbs.db`, `.DS_Store`, `desktop.ini`, a zero-byte `.png`, a `.txt`. |
| `11_truncated.cbz` | Cut at 62 %. The central directory is gone; pages must be recovered by walking local headers. |
| `12_giant_page.cbz` | One 12000×12000 page — 144 MP decoded — among three ordinary ones. |
| `13_outline.pdf` | Three pages under a real `/Outlines` tree, one title in CJK. |
| `14_solid.cb7` | Solid 7z: random access is impossible, page *N* costs pages 1…*N*. |
| `15_rar5.cbr` | RAR5. |
| `16_avif.cbz` | AVIF pages — what a modern scanner actually emits. |
| `17_huge_2gb.cbz` | ~2.25 GiB, with real pages on **both sides** of the 2³¹-byte offset. |

Two of those deserve an explanation.

**`17_huge_2gb.cbz` — why 2 GiB and not 4.** 4 GiB is where ZIP64 becomes mandatory, and
everybody tests it. 2 GiB is where an entry offset kept in a *signed 32-bit int* silently
wraps: reads below the line succeed, reads above it return garbage. Pages `tail001`…`tail004`
sit past the boundary specifically so that a truncated offset fails loudly. The bulk of the
file is `STORED` filler under `filler/*.bin` — free to produce, and `EntryFilter` rejecting
it by extension is itself worth asserting.

**`12_giant_page.cbz` — why the file is only 11 KB.** The giant page is a diagonal ramp, so
it deflates to almost nothing. That is on purpose: the stress this case applies is the
144-megapixel *decoded* surface against the memory budget, not the bytes on disk.

### Cases that need an external tool

Three cases cannot be produced from the stdlib. The script probes for each at run time and
**skips it with a printed reason** rather than failing:

| Case | Needs | Licence | Vendored? |
|---|---|---|---|
| `14_solid.cb7` | `7zz` / `7z` / `7za` | LGPL-2.1 (p7zip) | No — install it if you want the case |
| `15_rar5.cbr` | `rar` | **Proprietary (RARLAB shareware)** | **No, and never** |
| `16_avif.cbz` | `avifenc` | BSD-2 (libavif) | No — install it if you want the case |

On RAR5 specifically: we will not ship RARLAB's `rar`, and we will not check in a
pre-built `.cbr` sample either. A machine without `rar` simply produces a corpus with that
case missing, and says so. This is a **generator-side** constraint only — libarchive reads
RAR5 through a clean-room implementation, so the reader carries no licence problem.

### Reproducibility

`corpus-expected-sha256.json` pins the digests of the 13 always-generated, pure-Python
cases. `--check` regenerates and compares, which is how CI notices that a refactor quietly
changed the corpus. The external-tool cases are not pinned (their encoders differ between
versions) and neither is `17_huge_2gb.cbz` (opt-in).

`03`, `04` and `05` share a digest — same bytes, three different extension spellings. That
is the point of those cases.

Determinism comes from: an explicit xorshift64\* PRNG rather than `random`; a fixed DOS
timestamp, `create_system` and `external_attr` on every ZIP entry; and a pinned zlib level.

### Known gaps

- **Pages are PNG only.** Real comics are mostly JPEG. Adding JPEG means either a
  hand-rolled baseline encoder or a Pillow dependency that would break `--check`.
  `TODO`: hand-roll a minimal baseline JPEG encoder.
- **No CP437-named entry.** Archives written by old WinRAR store names in the OEM code page
  without the UTF-8 flag, and mojibake there is a real field bug. `zipfile` always sets the
  UTF-8 flag for non-ASCII names, so producing one needs post-processing of the raw headers.
  `TODO`.
- `14_solid.cb7` will not open yet: the native build sets `ENABLE_LZMA=OFF`
  (`source/libarchive/src/main/cpp/CMakeLists.txt`, `TODO(phase6)`). The case exists so
  phase 6 has something to open on day one.
