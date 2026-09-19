# Accessibility audit (M6) — findings only

A read-only pass over every UI module's `src/main`, against the four M6 criteria: touch targets
≥ 48 dp, layouts that survive 200 % font scale, text contrast ≥ 4.5:1, and nothing conveyed by
colour alone. **No code was changed.** Each finding names a file and line so the owning lane can
work from it directly.

Audited at `main` @ `b3aff91`. Modules: `feature/library`, `feature/reader`, `feature/settings`,
`feature/remote`, `feature/widget`, `core/ui`, `core/gpu`.

## What this pass can and cannot establish

Static reading finds missing labels, state that never reaches semantics, and fixed dimensions
around text. It cannot measure a rendered touch target, a wrapped line at 200 % scale, or a
contrast ratio — and for this app contrast is **not statically knowable at all**, because the
theme is dynamic colour drawn from the user's wallpaper at runtime. Everything under *Contrast*
is therefore a pointer for an on-device pass, not a verdict.

Severity is about what a TalkBack user loses, not about how hard the fix is.

## A. State conveyed visually only — a TalkBack user cannot perceive it

The thumbnail strip is the concentration of this, and all three are the same root cause: the
cell's semantics label is `reader_page_indicator_desc` ("Page 3 of 45") and nothing else, so
every cell sounds identical apart from its number.

| # | Where | Finding | Severity |
|---|---|---|---|
| A1 | `feature/reader/src/main/kotlin/com/absolutex/feature/reader/ThumbnailStrip.kt:97`, label set at `:100` | The page you are currently on is marked **only** by a 2 dp primary-coloured border. The label does not say "current", so the strip gives a TalkBack user no way to tell where they are. | High |
| A2 | `…/ThumbnailStrip.kt:111` (the bookmark marker), label set at `:100` | Bookmarked pages draw a marker in the corner. Bookmark state never reaches semantics, so it is invisible to TalkBack. | High |
| A3 | `…/ThumbnailStrip.kt:98` | `combinedClickable`'s long-press toggles the bookmark. Long-press is not a gesture TalkBack exposes, and there is no custom accessibility action, so **bookmarking from the strip is unreachable** without sight. | High |

A1 and A2 are label content. A3 needs a real accessibility action, not a longer label.

## B. Semantics that resolve to the wrong announcement

| # | Where | Finding | Severity |
|---|---|---|---|
| B1 | `feature/remote/src/main/kotlin/com/absolutex/feature/remote/ServerListScreen.kt:209-210` | The row sets `contentDescription` to the server name on the `ListItem` itself. A `contentDescription` on a parent **replaces** its descendants' text, so the `supportingContent` subtitle (transport kind · username, built at `:252`) is dropped from the announcement — sighted users see it, TalkBack users do not. The same modifier chain adds `.clickable` with no `Role.Button`, so the row does not announce as actionable either, although tapping it opens the editor. | Med-High |
| B2 | `feature/reader/src/main/kotlin/com/absolutex/feature/reader/TocPanel.kt:35` | A `Text` made clickable with no `Role.Button`. TalkBack reads the chapter title as static text and never says it can be activated, so the table of contents reads as a list of labels rather than a list of destinations. | Medium |
| B3 | `feature/remote/src/main/kotlin/com/absolutex/feature/remote/ServerFormScreen.kt:286` | `contentDescription` is set to the same string the `Text` already displays. Harmless today — it is one source — but it is a redundant override that will read oddly if the visible text ever gains formatting the description does not. | Low |

## C. 200 % font scale

| # | Where | Finding | Severity |
|---|---|---|---|
| C1 | `core/gpu/src/main/kotlin/com/absolutex/core/gpu/ColourPanel.kt:221` | Each colour slider's name sits in a **fixed `width(136.dp)`**. The longest label is already "White-balance strength"; at 200 % scale it cannot fit and will wrap or clip inside a box that does not grow. This is the clearest font-scale break in the tree. | High |
| C2 | `feature/reader/src/main/kotlin/com/absolutex/feature/reader/ThumbnailStrip.kt:96` and `:65` | Cells are a fixed `size(56.dp, 76.dp)` inside a fixed `height(84.dp)` row. While a thumbnail has loaded this is only an image, but the pre-decode fallback at `:110` is a text page number, which clips at large scale. Fixed *image* sizing is legitimate; the text inside it is what needs to give. | Low-Med |

## D. Touch targets

Mostly healthy, and worth being precise about why. Material 3's own controls — `TextButton`,
`IconButton`, `Slider`, `FloatingActionButton` — apply `minimumInteractiveComponentSize()`
internally, so their **touch target is 48 dp even where the drawn control is 40 dp**. Those are
not findings, and should not be "fixed" by padding them.

The exception is a raw `Modifier.clickable` on something that is not a Material component,
which gets no such expansion.

| # | Where | Finding | Severity |
|---|---|---|---|
| D1 | `feature/reader/src/main/kotlin/com/absolutex/feature/reader/TocPanel.kt:35-41` | A clickable `Text` sized only by 12 dp top and bottom padding plus its own line height. That clears 48 dp at default font scale and stops clearing it as the scale goes **down**; there is no `heightIn` floor. Same element as B2. | Medium |
| D2 | `feature/library/src/main/kotlin/com/absolutex/feature/library/Space.kt:13` | `Space.MinTouchTarget = 48.dp` is `internal` to `feature/library`. `reader`, `settings`, `remote`, `gpu` and `widget` cannot reference it, so the one rule that says "nothing interactive is smaller than this" exists in exactly one module. Structural, not a defect — see *Seams wanted*. | Structural |

## E. Contrast — needs a device, not a reader

| # | Where | Finding |
|---|---|---|
| E1 | `core/ui/src/main/kotlin/com/absolutex/core/ui/Theme.kt:24-29` | The scheme is `dynamicLightColorScheme` / `dynamicDarkColorScheme`, derived from the user's wallpaper. **No contrast ratio in this app can be verified from source.** Material's tonal system is designed to keep the `on*`/surface pairs compliant, but that is a property of the generator, not something this repo asserts. Needs Accessibility Scanner against at least one heavily saturated wallpaper. |
| E2 | `core/ui/src/main/kotlin/com/absolutex/core/ui/Theme.kt:31-33` | `trueBlack` overrides `background` and `surface` to pure black but leaves `onSurfaceVariant` and the `surfaceContainer` family exactly as the dynamic scheme produced them for a *tonal* dark surface. Light-on-black gets **better**, so `onSurface` is safe; the pair to actually measure is muted `onSurfaceVariant` text sitting on pure black. |
| E3 | `feature/reader/src/main/kotlin/com/absolutex/feature/reader/ThumbnailStrip.kt:95` and `:110` | The page-number fallback is default-coloured text on a `surfaceVariant` fill. Worth measuring in the same pass; it is the one place a text/background pair is chosen locally rather than by a Material component. |

## F. Widget (Glance, resolved outside Compose)

`feature/widget/.../ContinueReadingWidget.kt` reads all its strings through
`context.getString`, which is correct for Glance and needs no change. Its tap target is a
`Column` with `.clickable` and no explicit role; Glance merges the child `Text` nodes, so the
announcement should carry the title and progress, and progress is stated in words at `:61-66`
rather than by the bar alone. Nothing here looks wrong, but Glance semantics differ from
Compose's and **none of it is verifiable without a device** — it needs its own TalkBack pass.

## Checked and cleared — please do not "fix" these

Recording these so the next pass does not spend the day re-deriving them.

- **`ServerListScreen.kt:71`, `:86`, `:201`** — `IconButton`/`FloatingActionButton` wrapping an
  `Icon(contentDescription = null)`. This looks like three unlabelled buttons and is not: each
  button carries its own `semantics { contentDescription = … }`, and a null description on the
  inner icon is the correct way to stop it being announced twice.
- **`LibraryBars.kt:52`, `:205`, `:221`** — the `✕` and `⋮` glyph buttons. Same pattern, labelled
  on the parent.
- **Material 3 controls at 40 dp drawn height** — touch target is already 48 dp, see *D*.
- **`ThumbnailStrip` cells at 56×76 dp** — comfortably over 48 dp.
- **`ReaderScreen.kt:527-530`** — the seek slider sets both `contentDescription` and
  `stateDescription`. This is the pattern the rest of the app should copy.
- **`SettingsControls.kt:63-64` and `:116-119`** — `toggleable`/`selectable` with an explicit
  `Role.Switch` / `Role.RadioButton` and a `stateDescription`, and the inner `Switch` given a
  null callback so the row owns the click. Also exemplary.
- **`LibraryBrowse.kt:232`, `:245`** — `clearAndSetSemantics` on composite rows, which is the
  right tool where a row must read as one thing.

## Seams wanted

- **Move `MinTouchTarget` into `:core:ui`** (from `feature/library/Space.kt:13`), so every lane
  can state the rule instead of re-deriving it. `:core:ui` currently holds only `Theme.kt`.
  Owner's call; D2 stays open until it exists.

## Device checks owed

1. Accessibility Scanner over library, reader chrome, settings, remote — light, dark, and
   `trueBlack`, against a saturated wallpaper (covers E1–E3).
2. TalkBack through the thumbnail strip: can you tell which page is current, which are
   bookmarked, and can you bookmark one at all? (A1–A3.)
3. Every screen at 200 % font scale, colour panel first (C1).
4. TalkBack over the widget on the home screen (F).
