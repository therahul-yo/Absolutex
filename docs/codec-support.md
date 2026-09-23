# Codec support (§6 M4, first item)

Write-up only. No code changes, and deliberately so: the two questions this closes are licence
questions, and the one it cannot close is a measurement that needs the reference device.

## What this answers, and what it does not

**Answered here, from primary sources:** whether JPEG XL and DjVu can enter the tree at all.

**Not answered here:** what Android API 33 actually decodes. That needs `ImageDecoder` on the
reference device, and nothing in this container can run it. Asserting a format table from
documentation — let alone from memory — is exactly the kind of claim this project does not
accept, so it is left as a device check rather than written down as fact. The shape of that
measurement is at the bottom.

## The problem this exists for

`EntryFilter.PAGE_EXTENSIONS` (`source/api`) lists sixteen extensions:

```kotlin
"jpg", "jpeg", "png", "webp", "avif", "heif", "heic", "gif", "bmp", "tif", "tiff",
// Phase 4: modern/high-bit-depth scan formats (JPEG XL, JPEG 2000 family).
"jxl", "jp2", "jpx", "j2k",
```

Some of those the platform cannot decode. The consequence is not an error — it is worse than
one. `PageImage.from` is documented to never throw for corrupt input: an undecodable page comes
back with `width <= 0` and is simply unreadable. So a scan of such pages produces a book with
the right page count where every page is permanently blank, and nothing tells the user why.

**The list cannot be trimmed to fix this.** A folder book's identity is
`BookIdentity.of(displayName, sizeBytes)` where `sizeBytes` is `images.sumOf { … }` over exactly
the entries passing `EntryFilter.isPage`. That sum keys reading progress, bookmarks and the
library `contentKey`. Removing an extension changes the sum for every folder book containing
one, which silently orphans that book's saved position on upgrade. The set is frozen for the
same reason a DataStore key is.

So the fix belongs at the decode end: a page that cannot be decoded should say so, and the
library should mark a book whose pages this device cannot read. That is a change in
`:core:decode` and the reader, not in the entry filter.

## JPEG XL — permissively licensed; the open question is size

**Licence: BSD-3-Clause. Verified**, not recalled — `libjxl/libjxl@main:LICENSE` is the
three-clause text, clause 3 being the no-endorsement clause, and the repository carries no
`COPYING`, `COPYING.LESSER` or `LICENSE.txt` at its root (all 404). Nothing about it conflicts
with the no-GPL/AGPL rule, and it would need one row in the README licensing table.

**Platform support:** JPEG XL is not something this codebase can assume. Whether any API 33
device decodes it is part of the device measurement below, and the answer decides whether the
question is "enable it" or "bundle libjxl".

**Cost: unmeasured, and I will not estimate it.** The APK budget is a single cumulative figure
gated against `.github/apk-size-baseline.json`, currently 10.2 MiB against a 10.4 MiB ceiling —
**177 KiB of headroom** at the time of writing. A decoder's contribution has to be measured by
building it arm64-only with the same `-Os -fvisibility=hidden` treatment `:source:libarchive`
gets, not guessed from an upstream release artifact built with different flags for a different
ABI. That build cannot run here (no NDK; the SDK host is blocked by this container's egress
policy).

**Recommendation:** do not pursue it until two numbers exist — the measured decode table, and a
measured arm64 `-Os` static size. If the size lands anywhere near the current headroom it is the
lead's ceiling decision, not a side effect of a formats PR. Until then `jxl` stays listed and
undecodable, which the decode-end fix above makes honest rather than silent.

## DjVu — excluded, and this one is final

**Licence: GNU GPL v2. Verified** — `djvulibre`'s `COPYING` is the GPLv2 text verbatim,
beginning "GNU GENERAL PUBLIC LICENSE, Version 2, June 1991".

The README's rule is absolute: *"No dependency is GPL or AGPL."* That settles it. DjVu support
would require either djvulibre or a clean-room decoder, and the second is not a comic reader's
job.

I checked `COPYING` only. If someone later believes a linking exception applies, that is a
separate claim needing its own evidence — it does not change the answer from this file.

**Recommendation:** closed. Not a gap to revisit unless a permissively licensed decoder appears.

## The measurement this still owes

On the reference device, through the real decode path — `PageImage.from` then `decodeBase`, not
a synthetic `ImageDecoder` call — for a page of each format inside a real archive:

| format | decodes? | notes |
|---|---|---|
| JPEG, PNG | | the baseline; if these fail the harness is wrong |
| WebP lossy, lossless, animated | | animated must yield the first frame, not a failure |
| HEIF / HEIC | | |
| AVIF | | corpus case `16_avif.cbz` already exists, gated on `avifenc` |
| GIF, BMP | | |
| TIFF | | listed in `PAGE_EXTENSIONS`; suspected undecodable |
| JPEG 2000 (`jp2`, `jpx`, `j2k`) | | listed; suspected undecodable |
| JPEG XL | | listed; decides the libjxl question above |

Fixtures for the formats the corpus lacks need `cwebp`, `heif-enc` and `avifenc`, none of which
are installable in this container, so they follow the established external-tool skip pattern
(`14_solid.cb7`, `15_rar5.cbr`, `16_avif.cbz`) and are generated on a machine that has them.
A fixture that cannot be verified to decode is worse than no fixture, so none are added here.
