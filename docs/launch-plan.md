# Public launch plan: from "works on my phone" to "try my app"

Audited at `main` @ `69df560` (28 Sep 2026). Main CI is green. Open work in flight:
onboarding (#141) and the short-PDF strip fix (#140), both in the lead's lane.

The goal: a post on social media that says *try my app* and sends strangers to it, without
the first click, the first install or the first ten minutes going wrong in public.

## Where things stand

The reader itself is ready to show. It has four archive formats plus PDF and EPUB, three
reading flows, tiled GPU rendering, colour correction, a real library, SMB/FTP browsing and
measured performance on the reference phone. Screenshots are clean and the landing page works
at phone widths (checked at 360×640 and 390×844). None of that needs to change for a launch.

What isn't ready is everything **around** the app: how a stranger gets it, what they see when
they open it with no comics on their phone, what happens when it misbehaves on a phone that
isn't a OnePlus 11R, and how they get the next version. One of these breaks the launch on the
very first click.

## Findings

Each one was checked against the code or the live repository, not taken from the README.

| # | Finding | Evidence | Impact |
|---|---|---|---|
| F1 | **The Download button leads nowhere.** Both releases (v0.1.0, v0.1.1) are still **drafts**. Drafts are invisible to everyone but the owner. | `github.com/therahul-yo/Absolutex/releases/latest` returns 302 to an empty `/releases` page for anyone else. The public API `/releases/latest` returns 404. The README and site buttons both point there. | **Blocker.** Every click from a post dead-ends. |
| F2 | **Komga and Kavita can be set up, but nothing syncs.** The Add-server form offers both, and the connection test passes. `onBookOpened`, `onPageSettled` and `onBookClosed` still have no callers. | `remote/sync/.../SyncController.kt:50` (`TODO(lead)`); `feature/remote/.../ServerFormViewModel.kt:166` | High. A power user's first bug report will be "sync doesn't work". |
| F3 | **No way to report a problem.** There are no issue templates (`.github/` holds only the PR template), no *Report a problem* row in Settings → About, and no crash log (there is deliberately no crash reporting). | `.github/`, `feature/settings/.../SettingsControls.kt:233` (`AboutRow` shows only the version) | High. Bugs from launch week go unreported or arrive as "it crashed" with no device or file. |
| F4 | **No update path.** A sideloaded APK never updates itself, and there is no update notice, no F-Droid or IzzyOnDroid listing and no Obtainium hint. | Distribution is GitHub Releases only. | High. Everyone who installs from the post stays on 0.1.1 forever. |
| F5 | **Marketing images are copyrighted comics.** The README screenshots and the benchmark book are *Absolute Batman*. | `docs/screenshots/*.jpg`, README | Medium for a social post, a **blocker** for any store listing. Keep DC pages out of promo material. |
| F6 | **A first run with no comics has no demo.** Onboarding (#141) handles picking a folder. Someone who clicks through from a post often has no CBZ on their phone at all. | #141's flow ends at the folder picker. | Medium. "Installed it, nothing to read, uninstalled." |
| F7 | **Tested on one phone.** Every measurement comes from the OnePlus 11R. Any arm64 phone on Android 13+ can install it, including mid-range ones. A 32-bit or Android 12 phone gets a bare "App not installed". | README *Platform floor*, *Measured on the reference device* | Medium. Samsung One UI, Pixel and Xiaomi SAF and file-manager quirks are unknown. |
| F8 | **The site has no real app images, no install steps and no privacy page.** On a phone it shows ASCII art only. It doesn't explain sideloading ("Install unknown apps", Chrome's "file might be harmful" warning) or what "flagship" means in practice. | `site/index.html` | Medium. Visitors from a post want to *see* the app, and sideloading scares off non-technical people. |
| F9 | **The README is mostly for contributors, and parts of it are stale.** It says remote browse #50 is "in review" (merged) and `:source:epub` is "not yet dispatched" (dispatched by #109). The module map is missing about 12 modules. "Built but not yet connected" is the first thing below the fold. | README *Status*, *Module map*, *Roadmap* | Low-Medium. Visitors from a post land here and read it as "unfinished". |
| F10 | **Release notes are internal PR titles**, such as "Cloud M2a: PKCE…" and "Lane B: hostile test corpus…". | Draft v0.1.0 / v0.1.1 bodies (`--generate-notes`) | Medium. The release page is the second page a visitor sees. |
| F11 | **Smart crop can't be turned off for comics.** `ReaderScreen` passes `cropEnabled = !isPdf`. There is no preference and no toggle, so a page cropped wrongly can't be fixed. | `feature/reader/.../ReaderScreen.kt:689`, `PageCanvas.kt:178` `TODO(lead)` | Low-Medium. |
| F12 | **Encrypted CBZ/CBR/CB7 get no password prompt.** PDFs do (#115). | `source/libarchive/.../LibArchiveSource.kt:122` `TODO(lead)` | Low. A rare file type, but it fails silently. |
| F13 | **The signing key is now permanent.** Once strangers install, losing `release.jks` or its passwords forces everyone to uninstall, losing their progress. `allowBackup="false"` means progress doesn't survive a phone change either. | `release.yml`, `AndroidManifest.xml` | Low today, catastrophic if the key is lost. |

Performance items that are **not** blockers. The README already reports them honestly: tap to
first page is 330 ms against a 250 ms budget, and 2 frames in 1,387 missed their deadline.
Just don't claim "zero dropped frames" in the post.

## The plan

Ownership follows the lanes in the README so nothing collides with the lead. The **Owner**
column holds things only you can do: repository settings, releases, secrets and devices.

### Phase 0: launch blockers (before any post)

| # | Task | Owner | Size |
|---|---|---|---|
| 0.1 | **Publish a release.** Rewrite the v0.1.1 notes by hand (see [Release notes](#release-notes-template)), then publish it as a normal release, *not* a pre-release, because `/releases/latest` skips pre-releases. Delete or leave the v0.1.0 draft. Check in a private window that the site button downloads the APK. | Owner | 30 min |
| 0.2 | **Merge onboarding (#141) and #140** once the lead's on-device swipe check passes. Onboarding is the first-run story, and launching without it wastes the post. | Lead | in flight |
| 0.3 | **Hide Komga and Kavita from the Add-server form** until sync is wired, or wire it (0.3b). Hiding means dropping the two `KindChip`s at `ServerFormScreen.kt:223-224`. Existing records stay untouched. | Agent 2 (network) | S |
| 0.3b | *(Instead of 0.3, if there's time)* Wire sync as `SyncController.kt:50` describes: three calls in `ReaderViewModel` plus the "Continue at page N from server" offer. | Lead | M |
| 0.4 | **Back up the signing key**: `release.jks`, both passwords and the alias, in two offline places. Record the certificate SHA-256 in the README so users can verify it. | Owner | 15 min |
| 0.5 | **Check the release build on 3 or more phones besides the 11R**: one Samsung (One UI 6/7), one Pixel, and one mid-range 6–8 GB phone. Use the [smoke checklist](#pre-release-smoke-checklist). Friends' phones count. | Owner | ½ day |

### Phase 1: first impression and trust (strongly recommended before posting)

| # | Task | Owner | Size |
|---|---|---|---|
| 1.1 | **Promo images with licensed comics.** Retake the screenshots and a 15–30 s screen recording with public-domain or CC-BY comics, such as *Pepper&Carrot* (CC-BY 4.0, credit David Revoy) or Golden Age public-domain books. Use these in the post, README and site. Keep DC pages out of anything promotional. | Owner + Lead | ½ day |
| 1.2 | **Site: add a strip of 3–4 real screenshots,** an *Install* section (download, then allow "Install unknown apps", then open) and a short FAQ: requirements (Android 13+, 64-bit, best on flagships), "why not Play Store yet", "is it safe" (open source, SHA-256 on each release). | Agent 3 (design) | S |
| 1.3 | **Privacy page** on the site (`/privacy`): no ads, no analytics, no crash reporting, network only for servers you add. It's cheap trust now, and Play will require it later. | Agent 3 | XS |
| 1.4 | **Split the README.** Keep the user-facing top (features, screenshots, requirements, install, **Known limitations**). Move *Status*, *Module map*, *Build*, contributor notes and benchmarks into `CONTRIBUTING.md` and `docs/architecture.md`. Fix the stale lines from F9 while moving them. | Agent 7 (docs/review) | S |
| 1.5 | **"Try it without your own comics"**: a small CC-BY sample CBZ, fully credited, attached to the release, linked from the site and from the empty library or onboarding. Don't bundle it in the APK (size gate). | Owner (asset) + Lead (link in onboarding) | S |
| 1.6 | **Settings toggle for smart crop** (F11): a `cropEnabled` preference in `RenderingPrefs`, passed where `PageCanvas.kt:178` says. | Agent 3 (settings) | S |

### Phase 2: a feedback loop for launch week

| # | Task | Owner | Size |
|---|---|---|---|
| 2.1 | **Issue templates**: *Bug* (app version, phone, Android version, file format, where the file lives: local, SAF or SMB, steps) and *File won't open* (format, size, whether another reader opens it). Turn on **Discussions** for questions and ideas, so Issues stays for bugs. | Agent 7 | XS |
| 2.2 | **Report a problem** row in Settings → About. It opens a pre-filled GitHub issue URL with the version, device model and Android version. The user sees and sends it, so nothing is sent automatically. | Agent 3 | S |
| 2.3 | **Local crash log.** An uncaught-exception handler writes the last stack trace to app storage. On the next launch, show "Absolutex closed unexpectedly. Report it?", which feeds 2.2. It stays on the device unless the user chooses to share it, which keeps the no-tracking promise. | Lead (app shell) | S–M |
| 2.4 | **Friendly error for encrypted archives** (F12): a password prompt like PDF's, or at least a clear "this archive is encrypted" message. | Agent 4 (archive/native) | S |

### Phase 3: after the post

| # | Task | Owner | Size |
|---|---|---|---|
| 3.1 | **An update path.** (a) Add an Obtainium link and badge to the README and site now; it tracks GitHub releases with no code. (b) Submit to **IzzyOnDroid**, which takes APKs from GitHub releases. (c) Optionally, an opt-in *Check for updates* in Settings that reads the GitHub releases API, off by default. | Owner / Agent 3 | S |
| 3.2 | **Google Play**: a developer account, the privacy page (1.3), licensed screenshots (1.1) and a closed test (new personal accounts must run a 14-day closed test with 12+ testers before production at the time of writing; check the current policy and start early). F-Droid *main* is harder because PDFium ships prebuilt. IzzyOnDroid is the realistic F-Droid-client route. | Owner | weeks |
| 3.3 | **Backup of reading state**: a `dataExtractionRules` allowlist (progress, bookmarks, settings; no credentials), so a phone change doesn't wipe history. It needs the lead's sign-off, like any storage change. | Lead | S |
| 3.4 | Remote covers, cloud sources and offline copies stay out of scope, as the README already says. Don't mention them in the post. | — | — |

### Suggested order

1. **Day 1**: 0.1, 0.4, 0.3; the lead finishes 0.2.
2. **Days 2–3**: 0.5 on friends' phones; 1.1 screenshots and video; 2.1 templates.
3. **Days 3–5**: 1.2, 1.3, 1.4, 1.5, 2.2. Cut v0.1.2 with anything 0.5 found.
4. **Soft launch**: share with 10–20 people (a Discord, r/comicbooks or r/manga *feedback* threads, friends) for about 3 days. Fix what they hit.
5. **Public post**, then 3.1(a) the same day, 2.3 and 3.1(b) within the week.

## Pre-release smoke checklist

Run this on each test phone with the **signed release APK** from the published release, not a
debug build.

- [ ] Install from the site's button in a mobile browser. Note every warning dialog the phone shows.
- [ ] First launch: onboarding appears, a folder can be picked, and the library fills with covers.
- [ ] Open a CBZ, a CBR (RAR5 if you have one), a PDF and a text EPUB. Each shows its first page.
- [ ] Turn 50 pages quickly, pinch-zoom a page, double-tap to zoom, and try a manga (RTL) book.
- [ ] Rotate the phone and try a two-page spread in landscape.
- [ ] Leave mid-book, swipe the app away, and reopen it. It resumes on the same page.
- [ ] Open a comic from the phone's own file manager, and from a WhatsApp or Telegram download.
- [ ] Add a second folder in Settings, then remove it.
- [ ] Connect to an SMB share if you have one, browse, and open a book.
- [ ] Turn on system dark and light modes and large text (200%), and check nothing clips badly.
- [ ] Try one corrupt or truncated file and one encrypted archive. You should get a message, not a crash.
- [ ] Uninstall, then install the *next* build over the previous one to confirm updates install cleanly (same key).

## Release notes template

```markdown
Absolutex 0.1.1 — first public beta

A fast, minimal comic and manga reader for Android: CBZ, CBR, CB7, PDF and EPUB, from your
phone or an SMB/FTP share, on a true-black screen.

**Requirements:** Android 13 or newer, 64-bit. Built for flagship-class phones; mid-range
phones work but aren't what it's tuned for.

**Install:** download `Absolutex-0.1.1.apk` below, open it, and allow your browser to
"install unknown apps" when asked. SHA-256 is attached; the signing certificate is
<SHA-256 fingerprint>.

**Known limitations in this beta**
- No automatic updates yet: watch this repo, or add it to Obtainium.
- Komga/Kavita progress sync, cloud storage and offline copies are not available yet.
- Tested on: OnePlus 11R, <phone>, <phone>.

Found a bug or a file that won't open? [Open an issue](…/issues/new/choose).
No ads, no analytics, no crash reporting. Apache-2.0.
```

## Draft post

> I built a comic and manga reader for Android. It's fast, true-black, has no ads, and it's
> open source.
>
> Absolutex opens CBZ, CBR, CB7, PDF and EPUB from your phone or a network share. Pages are
> rendered in tiles on the GPU, so zooming into a big scan stays sharp at 120 Hz. RTL manga
> mode, two-page spreads, colour correction, and a library with real covers.
>
> It's a beta (Android 13+, 64-bit), so I'd love feedback: absolutex.vercel.app
>
> [15–30 s screen recording with a CC-licensed comic]

Say "beta" and name the limits up front. People forgive a beta that said so. They don't
forgive a "try my app" that crashes on their phone.

## Definition of done: "confident to post"

- [ ] The site's *Download* button gets the APK in a private browser window.
- [ ] Onboarding is merged and a first run with no comics has a sample to open.
- [ ] Nothing in the UI promises a feature that does nothing (Komga/Kavita hidden or wired).
- [ ] At least 3 phones besides the 11R passed the smoke checklist.
- [ ] Screenshots and video in the post use licensed comics.
- [ ] Anyone can report a bug in two taps (issue templates plus a Settings link).
- [ ] The signing key is backed up in two places.
