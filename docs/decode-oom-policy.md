# Decode failure vs decode OOM — policy recommendation

Status: **recommendation only — no code changed.** Written by Agent07 from the main-branch
sweep that followed the `runCatching`/cancellation fixes. Implementation belongs to the lane
that owns the base-layer and export allocations (see *Where this should land* at the end).

Anchored at `main` @ `b983174`. Citations name files and symbols rather than line numbers,
because line numbers drift between commits and symbols do not.

## The question

When a decode throws, what should happen? Today the codebase gives one answer for every
`Throwable`, and it needs two — because a corrupt page and an out-of-memory page differ in
**blame**, **retryability**, and **what the app should do next**.

## What the repository has already decided, twice

The project already has an explicit OOM policy, and it is **bound the allocation before it
happens**, not catch the Error:

- `source/api/src/main/kotlin/com/absolutex/source/ComicInfoParser.kt` (`MAX_BYTES` KDoc):
  *"The DOM builder would allocate until OutOfMemoryError — an Error, not an Exception, so it
  escapes the 'never throw, return null' contract and takes the library scan down with it.
  **Refusing oversized input is cheaper than catching OOM, which is not reliably recoverable
  anyway.**"*
- `remote/core/src/main/kotlin/com/absolutex/remote/core/Http.kt`: a response body cap exists
  because *"without a bound a server answering with something enormous — by fault or by malice
  — is an OutOfMemoryError rather than an error we can report."* `requestStream` exists so that
  a `200` answering a `Range` request is abandoned *"having transferred nothing"*.

Both places treat OOM as something to be **prevented by construction**. Neither catches it.

## The gap: decode does the opposite

Decode catches broadly and returns `null`, so an OOM is currently **indistinguishable from a
corrupt JPEG**:

| Site | Code | Effect |
|---|---|---|
| `core/decode/…/PageImage.kt` — `PageImage.from` | `runCatching { BitmapRegionDecoder.newInstance(...) }.getOrNull()` | OOM → no region decoder |
| `PageImage.kt` — `decodeThumbnail` | `runCatching { ImageDecoder.decodeBitmap(...) }.getOrNull()` | OOM → null thumbnail |
| `PageImage.kt` — `decodeTile` | `runCatching { rd.decodeRegion(rect, HARDWARE) }.getOrElse { … ARGB_8888 … }` | **any** `Throwable`, OOM included, falls back to the *software* path |
| `core/thumbnails/…/ThumbDecoder.kt` — `decodeAt` | `runCatching { …decodeBitmap… }.getOrElse { throw asIOException(e) }` | OOM → laundered into `IOException("Thumbnail decode failed")` |
| `feature/reader/…/ReaderViewModel.kt` — `baseLayer` | `runCatching { image.decodeBase(width, height) }.getOrNull()` | OOM → `null` → treated as a failed decode |

Because `runCatching` catches `Throwable`, every one of these converts an `Error` into the same
`null`/`IOException` an ordinary corrupt page produces.

## Why that matters — four concrete scenarios

1. **The retry trap.** `ReaderViewModel.bases` documents *"A failed decode is not
   remembered, so a retry decodes again."* When the cause is OOM, "retry" means *allocate the
   same ~23 MiB again on a heap that just failed to allocate it* — the unreadable-page retry
   affordance becomes a loop that walks the process toward lmkd.
2. **The strip re-OOMs per bind.** `core/thumbnails/…/ThumbnailPipeline.kt` wraps the load in
   `runCatching`, and turns a disk-decode failure into "evict the entry and report a miss". A
   thumbnail OOM therefore **deletes a valid cache entry** and re-derives it from source on the
   next bind — a 45-page strip can re-attempt a decode that OOMs, page by page.
3. **The fallback runs the more expensive path.** `PageImage.decodeTile` falls back from
   `HARDWARE` to `ARGB_8888` on *any* failure. That fallback exists for encoders that reject a
   hardware config — but if the cause was memory, it replaces a dmabuf-backed (GPU) bitmap with
   a 4-bytes-per-pixel software allocation. Under pressure that is strictly worse: the one path
   that should shed is the one that escalates.
4. **The user is told the wrong thing.** The page-level failure state (`ReaderViewModel.failPage`,
   `_failedPages`, the retry that clears the mark) says *this page could not be read*. For an OOM
   that is false: the page is fine, the device was short of memory. Both the string and the
   affordance mislead.

## Recommended policy

Classify by **cause**, never by symptom.

### Corrupt or unsupported input

`ImageDecoder.DecodeException`, `IOException`, a `null` from `BitmapFactory`, zero dimensions.
The current behaviour is right and should be kept:

- page-level, deterministic, **sticky** — marked failed and never cached
  (*"Never cache poison"*, `ReaderViewModel.pageImage`);
- **manual** retry only, which clears the mark and re-decodes;
- the cost is bounded to that one page;
- message: *this page could not be read*.

### Out of memory

`OutOfMemoryError` is a **resource event**, not a property of the page:

- **never** mark the page failed, and **never** show the unreadable-page string for it;
- **shed first** — the machinery already exists: `ReaderViewModel.onTrimMemory` halves the tile
  budget and prunes `bases` to the settled page's size. An OOM handler should invoke that same
  shed, then retry **once**;
- if the retry also OOMs, surface a **distinct, transient** state (resource pressure, retryable)
  instead of a corrupt-page state, and stop the batch that produced it rather than letting
  prefetch or the strip keep firing;
- never let an OOM evict a valid disk cache entry;
- where OOM must be caught, catch **`OutOfMemoryError` explicitly and narrowly** at the decode
  boundary — not as a side effect of a broad `runCatching`, which also launders unrelated
  `Error`s (assertion failures, `StackOverflowError`) into "unreadable page".

### And the structural half, following the repository's own precedent

The budget arithmetic already exists and is already tested — `MemoryBudget.bytesForPage` and
`MemoryBudget.pagesResident` (**`core/decode/…/MemoryBudget.kt`**, exercised by
`MemoryBudgetTest` and `MemoryBudgetEdgeTest`) — but **nothing in production consults them at
allocation time**. They are referenced only from tests. Closing that is the real fix: decide
*before* allocating, with a pure function, instead of attempting and catching.

Bound the two allocations that are actually unbounded:

| Allocation | Worst case | Bounded? |
|---|---|---|
| Tile (`TileGrid.TILE_SIZE` = 512) | 512 × 512 × 4 = **1.0 MiB** | yes, by construction |
| Base layer, viewport-sized (reference 1240 × 2772) | ≈ **13.1 MiB** | no |
| Base layer at source (3057 × 1988, since `MAX_BASE_EDGE` = 1.25× the viewport exceeds it) | ≈ **23.2 MiB** | no |
| Export render (`EXPORT_MAX_EDGE` = 3000) | up to ≈ **34 MiB** software | no |

So the base layer and the export render need a pre-check against the remaining budget, with LRU
eviction to make room, rather than an attempt-and-catch. Sizing context: the budget is 15 % of
RAM with a 256 MiB floor (≈ 1.14 GiB on the reference phone), against a measured steady state of
150 MB PSS / 304 MB RSS after 12 page turns (`README.md`, *Measured on the reference device*).
The reader normally sits **far** under budget, so a decode OOM means something abnormal — a
pathological page, a repeated re-decode on resize, or system-wide pressure. That is exactly why
it should be treated as transient and shed-for, rather than recorded as a broken page.

## Why this is a policy and not a `catch (e: OutOfMemoryError)` patch

The decision is a **pure function**: *given W × H and the remaining budget, decode now,
evict-then-decode, or refuse?* Being pure, it is JVM-testable exactly the way
`MemoryBudgetEdgeTest` already tests the budget arithmetic. Catching the Error is the part that
cannot be tested and cannot be relied on; computing the answer is the part that can. That is the
same reasoning `ComicInfoParser` and `Http` already applied, and decode is the last place that
has not.

## Where this should land

The base-layer and export allocations sit next to the M10 prefetch-budget work, and doing both
in one pass is how this stays one policy rather than two. Deliberately **not** bundled into the
cancellation fixes for `ReaderViewModel.thumbnail` and `exportPage`: those wrap a real suspension
point and need cancellation rethrown (see `core/data/…/RunCatchingCancellable.kt`), which is a
separate concern from memory. Mixing them would make each harder to review and neither testable.

