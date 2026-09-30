# Absolutex

**A beta comic and manga reader for flagship Android phones.**

**[Download the latest APK](https://github.com/therahul-yo/Absolutex/releases/latest)** · [Website](https://absolutex.vercel.app/) · [Developer docs](docs/DEVELOPMENT.md)

<p align="center">
  <img src="docs/screenshots/onboarding-welcome.jpg" width="180" alt="Onboarding: the pixel-block ABSOLUTEX over a drifting field of halftone characters, and the tagline Every panel, pin-sharp">
  <img src="docs/screenshots/library.jpg" width="180" alt="The library: pixel-block title, the Continue reading wheel with the current book centred, and a grid of comic covers under a floating section bar">
  <img src="docs/screenshots/reader-controls.jpg" width="180" alt="The reader's controls: page thumbnails, a seek slider and a bookmark">
</p>
<p align="center">
  <img src="docs/screenshots/zoom.jpg" width="180" alt="The same page zoomed in on Batman, the linework still sharp">
  <img src="docs/screenshots/epub-contents.jpg" width="180" alt="A 2,734-chapter web novel with its contents list and in-book search open">
  <img src="docs/screenshots/onboarding-reading.jpg" width="180" alt="Onboarding: choosing a reading direction from three cards, Left to right, Manga and Vertical">
  <img src="docs/screenshots/settings.jpg" width="180" alt="Settings: night mode, true black, and the library's folders">
</p>

The current download is **v0.1.4**. Open comics, manga, PDFs and EPUBs from your phone or an
SMB/FTP share and read them on a true-black screen. Comic and PDF pages use tiled GPU rendering.
Performance has been measured only on a OnePlus 11R; there is no frame-rate guarantee.

## Features

**Reads what you have**
- Comic archives: CBZ, CBR (RAR4/RAR5), CBT and CB7 (LZMA/LZMA2; encrypted 7z is unsupported); folders of loose images
- PDF, including password-protected files
- EPUB: fixed-layout comic EPUBs as pages, novels and web novels as reflowable text
- Opens from any folder you pick, from the file manager, or from SMB and FTP/FTPS shares

**Reading comics and PDFs**
- Left-to-right, right-to-left (manga) and vertical reading, chosen per book
- Single page, two-page spreads in landscape, and a continuous vertical strip (the default for PDFs)
- In the strip, pinch zooms the whole column and a finger pans across it, like a document reader
- Pinch and double-tap zoom, tap zones mirrored for right-to-left books
- A seek slider with haptic ticks, a thumbnail strip, bookmarks and a table of contents
- Dark pages for documents, rotation lock, keep-screen-on, volume-key, keyboard and gamepad turning
- Finishing a book offers the next one in the series; save any page as an image

**Picture quality, on the GPU**

Colour correction, Mitchell/Lanczos upscaling and matching the background to the page are optional
and off by default. The default sampler is the platform filter.

- Colour correction: brightness, contrast, saturation, vibrance, warmth and gamma
- Smart border crop, on by default with a switch to turn it off; PDFs are never cropped
- Mitchell and Lanczos upscaling for sharp text when zoomed
- Optional background matching, which keeps working when margin trimming is off

**Reading novels**
- Pages that turn like a book, or one continuous scroll per chapter, with adjustable text size
- The book's own contents list, and search across every chapter with the match highlighted
- Links inside the book work, and your place is saved and restored

**Library**
- Comics, Recent, Favourites and Documents, switched from a floating bar
- A Continue reading wheel that spins and clicks into place; long-press to take a book off it
- Real cover art, series stacks and "new" badges; dense grids become a wall of covers
- Reading stats: pages read, time spent reading and your daily streak
- Search across the whole library, sort by name, date or size, grid or list
- Folders picked through Android rescan when the library screen opens; they are not watched live

**Design**
- Minimal Material 3 in true black, pixel-block titles, motion and haptics
- A one-time setup on first launch: folders, reading direction and a few privacy choices
- Resumes the last book you were reading when the app opens

## Install

Download the APK from the [latest release](https://github.com/therahul-yo/Absolutex/releases/latest)
and open it on your phone (allow installs from your browser or file manager when Android asks).
Updates install over it with your library and reading positions kept. Version 0.1.3 updates to
0.1.4 in place with the same signing key; downgrading from 0.1.4 to 0.1.3 is not supported.

**Requirements:** Android 13 or newer on a 64-bit flagship-class phone (Snapdragon 8 Gen 1-class
or better). Developed and measured on a OnePlus 11R.

The supported floor is Android 13+, arm64 and a flagship-class Snapdragon phone. Testing has
covered only a OnePlus 11R; other phones and providers are unverified.

## Known limitations

- Encrypted CB7/7z is unsupported. Large-archive CB7 paging was checked on one phone only.
- Classic ZIP encryption (ZipCrypto) prompts for a password. AES-encrypted ZIP and encrypted
  7z are unsupported; password-protected RAR/CBR is untested.
- Komga/Kavita sync is not wired and those server types are hidden. Cloud sources and offline
  copies are not connected.
- The OnePlus 11R benchmark recorded 2 missed deadlines in 1,387 frames and a median tap-to-first-page
  time of 329.8 ms, above the 250 ms target. See the [measurements](docs/DEVELOPMENT.md#measured-on-the-reference-device).

## What's new in v0.1.4

- CB7/7z with LZMA/LZMA2 opens. Large solid 7z uses a single background decode and cached pages
  when eligible, avoiding repeated decoding on each turn; encrypted 7z remains unsupported.
- An **Open file** button, clearer empty states and more file-manager VIEW types.
- Settings → Backup exports/imports reading data and preferences as merge-only JSON through the
  system picker. The file contains book names, never server records, credentials or folder grants.
  Text-EPUB position inside a chapter and the tap-guide flag are excluded; after restore a text
  EPUB reopens at its chapter start.
- Background matching works with cropping off; reader option chips wrap; password entry avoids
  password-manager autofill; Cancel leaves the reader.
- A scan that cannot read a folder preserves its books. Opening a book puts it in Recent without
  counting a page read; export results show counts and learned page counts stay current.
  An upgrade cleanup removes old page-zero progress rows that represented Mark unread.
- Aligned home header actions, a clearer Open file icon, and a SETTINGS title with a back arrow.
- One-tap Enhance in bitmap/PDF reader chrome, off by default: Mitchell upscaling, contrast 1.05
  and vibrance 0.05. Custom settings are retained when the preset is turned off.

CB7 checks covered small and 83 MB solid/non-solid archives on one OnePlus 11R. Solid-archive
performance was measured on a laptop; one 300-page solid CB7 device pass opened its first page in
about 0.5 seconds and passed jump checks. Enhance strengths were judged by eye on one page only.
One measurement on 30 September 2026 on a OnePlus 11R after paging and jumping through a
564 MB CBR: about 171 MB process PSS (183 MB right after seek jumps); the project budget is
150 MB. This differs from the older 12-page-turn measurement in DEVELOPMENT.md. See the
[v0.1.4 release notes](https://github.com/therahul-yo/Absolutex/releases/tag/v0.1.4) for test scope and limitations.

## Updates

Absolutex does not update itself and has no update check. To hear about new versions, add
`https://github.com/therahul-yo/Absolutex` to [Obtainium](https://github.com/ImranR98/Obtainium),
which watches the releases page and installs the new APK, or use **Watch → Custom → Releases** on
this repository.

## Privacy

No ads, no analytics, no crash reporting. Reading files stay local unless you add a network share
yourself. Document covers stay hidden unless you turn them on, so private PDFs never show on your
shelf.

## License

Apache-2.0 — see [LICENSE](LICENSE). No dependency is GPL or AGPL; RAR support comes from
libarchive's clean-room readers, never RARLAB's UnRAR. Details are in the
[developer docs](docs/DEVELOPMENT.md#licensing).

| Component | Licence | Note |
|---|---|---|
| libarchive | New BSD | RAR4/RAR5 readers |
| PDFium | BSD-3-Clause | Bundled permissive dependencies |
| xz / liblzma | 0BSD | Statically linked, decoder only |

## Contributing

Building from source, the module map, measurement rules and how the agent lanes work are all in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
