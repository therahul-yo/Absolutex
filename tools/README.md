# `tools/`

| Script | What it does |
|---|---|
| `make-corpus.py` | Generates the §8 hostile test corpus. Below. |
| `check-strings.py` | Fails on **new** hardcoded user-visible strings in the UI modules. Pure stdlib, no Gradle, no SDK. Below. |
| `check-translations.py` | Fails on a *present* translation that disagrees with English — placeholders, plural forms, stale keys — and on a locale shipping without a named reviewer. Pure stdlib. Below. |
| `check-apk-size.py` | Breaks the release APK down by category and fails on a size regression. Run by CI; see `.github/workflows/README.md`. |
| `check-startup-budget.py` | Enforces the §3 300 ms P90 cold-start budget against Macrobenchmark JSON. Macrobenchmark has no assertion API, so the gate lives here. |
| `run-benchmark.sh` | Drives the Macrobenchmarks through `am instrument`, keeping the app installed so the staged corpus survives between runs. |

## Proving a checker actually checks

Every gate in this directory is asserted by mutating it and watching the assertion fail. Two
traps have caught us more than once, both of which make a broken checker look like a working
one:

**A mutant cannot die in a path the probe never executes.** Break a rule, run the tests, and if
they still pass the usual conclusion — "the mutation was harmless" — is usually wrong. More
often the fixture never reached the mutated line. The `%%` case in `check-translations.py` had
the same literal on both sides of the comparison, so miscounting it cancelled out; the
regional-locale fallback had no fixture using a regional locale at all; `pagesPerDay`'s
distinctness survived a mutation that replaced the page number with a different *deterministic
function* of the page number, which still collapsed duplicates. In each case the fix was a new
fixture that exercises the line, not a shrug.

**A mutation has to be able to fail.** Before concluding a rule is untested, check the mutation
actually changes behaviour for the input at hand. Writing one that does not is easy, and it
looks exactly like a gap in coverage.

And when checking a gate's exit code from a shell, remember `$?` after a pipeline is the *last*
command's status. `tool | grep foo; echo $?` reports grep's verdict, not the tool's, so a gate
that correctly exited 1 reads as 0. Use `${PIPESTATUS[0]}`, or run it without the pipe.

## `check-strings.py` — no new hardcoded strings (i18n milestone 1)

A string that never reaches `strings.xml` cannot be translated, and it is invisible in
review: `Text("Retry")` and `Text(stringResource(R.string.reader_retry))` look alike when
you are skimming a diff. The pseudo-locale render test catches these too, but it needs the
SDK and an emulator. This needs neither, which is why it belongs in the hygiene job.

```sh
python3 tools/check-strings.py              # the gate: fails on anything not baselined
python3 tools/check-strings.py --check      # self-test the detector; reads no sources
python3 tools/check-strings.py --list       # every finding, baselined or not
python3 tools/check-strings.py --update-baseline
```

### What it looks at

`src/main` of the modules that draw UI — `feature/*`, `core/ui`, `core/gpu` — at these call
sites: `Text(...)` (positional or `text =`), `contentDescription` / `stateDescription`,
`Toast.makeText`, `showSnackbar`, and the `NotificationCompat` setters.

Interpolated text is reported separately as `text-interpolated`, because it is a different
bug: `Text("${stringResource(R.string.library_layout)}: ${layout.label()}")` *is*
externalised, but gluing translated fragments with a hardcoded `: ` assumes English word
order and punctuation. The fix is a positional format string, not a resource lookup.

The other 13 modules are deliberately out of scope. An over-broad sweep of all 20 returned
276 candidates, of which 269 were SQL in `@Query`, log tags, `@Suppress` names or exception
messages. Scanning them is noise.

### Suppression

```kotlin
// i18n-ignore: decorative glyph; the label is on the parent's semantics block
) { Text("✕") }
```

On the line or the line above. **The reason is required** — a bare `// i18n-ignore` is
itself a finding. A reviewer should see the justification in the diff rather than having to
know that a table of exempt code points exists somewhere.

### The baseline

The milestone is to reject *new* literals, so known ones are pinned in
`strings-baseline.json` and reviewed like any other diff. Entries are keyed by **path, kind
and the string itself, with an occurrence count — never by line number**. Two reasons:

- a line-numbered baseline goes stale on every unrelated edit above it, and a gate that
  cries wolf is disabled within a week;
- the count is load-bearing. `LibraryBars.kt` holds two identical `Text("✕")` calls; keyed
  without a count they collapse to one entry and a third copy passes unnoticed.

Shrinking that file is the point — every entry is either a milestone-2 externalisation or
wants an `// i18n-ignore:` comment. Fixing one leaves a stale entry, which is reported as a
note, never a failure: the gate must not punish the work it exists to encourage.

### Known gaps

- **Only direct call sites.** A helper that *returns* English — `formatSize()` yielding
  `"4.2 MB"`, `Metadata.displayName` appending `"Vol. "` — is invisible here, because the
  literal is nowhere near a `Text(`. Both are known and queued; a detector for them would
  have to follow values across functions, and would false-positive on every log message.
- **No XML scanning.** `android:text` on a layout would be missed. The app has no layout
  XML today (`android:label` in the manifest already points at `@string/app_name`), so this
  buys nothing yet.

## `check-translations.py` — translations that are present and wrong (i18n milestone 5)

A bad translation does not look like a bug in review. `"Page %1$d of %2$d"` coming back as
`"Seite %1$d"` reads fine in a diff and throws `MissingFormatArgumentException` on a device; a
Polish plural missing `few` silently renders the `other` form for 2, 3 and 4. Neither is visible
to anyone who does not read the language, so the gate lands before the translations do —
exactly as `check-strings.py` landed before externalisation.

```sh
python3 tools/check-translations.py            # the gate
python3 tools/check-translations.py --check    # self-test over fixtures
python3 tools/check-translations.py --list     # every locale found and what was compared
python3 tools/check-translations.py --refresh-cldr   # re-pin the plural data (network)
```

### What fails, and what deliberately does not

**A missing string is not a failure.** Android falls back to English per resource, which is the
designed behaviour and the normal state of a translation in progress. Which locales are complete
enough to ship is the review-status list's job, not this gate's.

Three ways a *present* translation is wrong:

| Kind | Caught |
|---|---|
| `placeholder` | Positions, conversion types or count disagree with English. Reordering is fine — that is what positional arguments are for. `%%` is a percent sign, not an argument. |
| `plural` | A `<plurals>` lacks a form its language requires. |
| `stale-key` / `not-translatable` | The key does not exist in English (renamed or removed), or English marks it `translatable="false"`. |

English's own plurals are checked too, as `english-reference`. If `other` were missing there,
every locale's argument comparison would be quietly meaningless rather than failing.

### Review state, and the rule it enforces

`translations-status.json` records who reviewed a locale, when, and against what. The rule it
exists for:

> **A locale does not enter `locales_config.xml` until it has a named reviewer.**

Until then its translations can sit in the tree and be checked for well-formedness, while no
user can select them. Machine-translating two hundred strings is easy; shipping them unreviewed
to people who read that language is how an app looks broken. `en` is the source language rather
than a translation, so it is exempt.

Only facts a person supplies are stored — `reviewer`, `reviewed_at`, `reviewed_against`, and
free-text `notes`. **State, coverage and staleness are computed**, never recorded: a written-down
percentage is wrong the moment English gains a string, and a file that can contradict the tree
is worse than no file. So `--list` derives each locale's state (`source` / `reviewed` / `draft`
/ `planned`) and its coverage from the resources themselves.

`reviewed_against` holds both a `commit`, for a human following the trail, and an
`english_digest`, which is what makes staleness answerable without git — it is a hash of every
translatable English string, so it changes when English strings change and not when anything
else does. Get the current value with `--digest` and paste it in when recording a review.

A review going stale is **reported, not failed**. English gaining a string would otherwise make
every reviewed locale fail at once, which would only teach people to stop adding strings; the
untranslated ones fall back to English exactly as designed, and the coverage figure drops to say
so.

What does fail: a locale in `locales_config.xml` with no named reviewer; a locale with resources
but no entry here at all; and a malformed entry — an unknown field, a reviewer with no date or
no basis, a `reviewed_at` that is not a date. The misspelled-field check matters more than it
looks: a typo'd key is how a review silently disappears while the reviewer believes it is
recorded.

### Where the plural forms come from

`cldr-plural-forms.json`, derived from CLDR's supplemental data — **not typed out here**. The
sets genuinely differ: English needs `one`/`other`, Polish `one`/`few`/`many`/`other`, Japanese
only `other`, Arabic all six. The file records the npm package, CLDR version, URL and SHA-256 it
came from, and `--refresh-cldr` regenerates it from that same pinned source, so the provenance is
executable rather than a comment. Hand-maintaining the table is how a language quietly gets the
wrong rules: French gained a `many` category in recent CLDR and nobody would have noticed.

Lookup walks from the most specific tag to the bare language, because CLDR keys are languages
and sometimes language-plus-region: `values-pt-rBR` resolves through `pt-BR` to `pt`, and
`values-zh-rCN` to `zh`. Qualifiers that are not languages — `values-night`, `values-sw600dp` —
are skipped rather than guessed at.

### Known gaps

- **`<string-array>` is not compared.** Nothing in the app uses one yet; an array whose element
  count differs between locales is a real bug and would need its own rule.
- **Nothing checks whether a translation is any good.** This proves structure, never meaning.
  That is what the milestone 5 review status is for, and no gate substitutes for it.

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
| `18_comicinfo_behind_junk.cbz` | `ComicInfo.xml` at a non-zero raw ordinal, behind junk, nested and mixed-case. |
| `19_fixed_layout.epub` | A conforming fixed-layout EPUB: `mimetype` first and STORED, SVG page images, hrefs climbing `../` out of `text/` into `img/`. |
| `20_epub_named_cbz.cbz` | **Byte-identical to `19`**, wearing `.cbz`. Anything that tells them apart is reading the name, not the content. |
| `21_epub_rtl.epub` | The same book declaring `page-progression-direction="rtl"` — how a manga EPUB says which way it reads. |
| `22_tar.cbt` | A real TAR: its magic sits at offset **257**, not at the start, so a sniffer reading only the first bytes misses it. |
| `23_cbz_with_pdf_inside.cbz` | A CBZ **storing a real PDF**, so `%PDF-` lands inside the first kibibyte. Searching for it before establishing the container hands a ZIP to PDFium. |
| `24_comicinfo_malformed.cbz` | A sidecar that is not valid XML. The metadata is lost; the book is not. |
| `25_comicinfo_oversized.cbz` | A sidecar past the 1 MiB parser cap — 1.5 MiB inflated from 5 KB, in an 8 KB file. |
| `26_comicinfo_corrupt_entry.cbz` | A sidecar whose deflate stream is corrupt, so **extraction** fails rather than parsing. |
| `27_comicinfo_two_sidecars.cbz` | A root sidecar and a nested one disagreeing; raw archive order decides which wins. |

Two of those deserve an explanation.

**`17_huge_2gb.cbz` — why 2 GiB and not 4.** 4 GiB is where ZIP64 becomes mandatory, and
everybody tests it. 2 GiB is where an entry offset kept in a *signed 32-bit int* silently
wraps: reads below the line succeed, reads above it return garbage. Pages `tail001`…`tail004`
sit past the boundary specifically so that a truncated offset fails loudly. The bulk of the
file is `STORED` filler under `filler/*.bin` — free to produce, and `EntryFilter` rejecting
it by extension is itself worth asserting.

**The four ComicInfo cases — why the sidecar needs its own row.** `01` already carries a
well-formed sidecar at the archive root and `18` carries a nested, mixed-case one at a non-zero
raw ordinal, so what was missing was every way a sidecar goes *wrong*. `24` cannot be parsed,
`25` is refused by the 1 MiB cap before parsing is attempted, and `26` cannot be extracted at
all — a distinction that matters, because that last one is the path a reader takes when the
sidecar is damaged rather than merely wrong, and a loader that turns it into a thrown exception
stops the book opening with pages that are perfectly readable. `27` pins the tie-break between
two sidecars, which is a property of entry order rather than path depth.

Verified against the production `ComicInfoLoader`: `24`, `25` and `26` each yield null metadata
with all six pages intact, and `27` resolves to the root sidecar.

**`20_epub_named_cbz.cbz` — why a duplicate earns its place.** It is the same bytes as `19`
under a different name, and that is the entire test: a reader that opens one and not the other is
dispatching on the extension. Two cases sharing a digest is the point, exactly as `03`/`04`/`05`
already do.

**`23_cbz_with_pdf_inside.cbz` — the case that was a live bug.** Until the magic-byte sniffer
landed, `openBook()` searched the whole first kibibyte for `%PDF-` *before* establishing what the
container was, so this file was handed to PDFium — which cannot open a ZIP — and the book failed.
It is `STORED` rather than deflated so the magic genuinely is in those bytes. Pinned here so the
ordering (structured signatures at fixed offsets first, the PDF scan last) cannot regress
unnoticed.

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

`corpus-expected-sha256.json` pins the digests of the 23 always-generated, pure-Python
cases. `--check` regenerates and compares, which is how CI notices that a refactor quietly
changed the corpus. The external-tool cases are not pinned (their encoders differ between
versions) and neither is `17_huge_2gb.cbz` (opt-in).

`03`, `04` and `05` share a digest — same bytes, three different extension spellings — and so
do `19` and `20`. That is the point of those cases.

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
