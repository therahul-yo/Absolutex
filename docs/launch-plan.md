# Public launch plan: current status and remaining checks

Rechecked against `main` @ `e2b37f9` (30 Sep 2026) and the public v0.1.3 release.
The public README and website describe v0.1.3; changes on main are labelled **Next release**.
A merged feature is not evidence that the downloadable APK contains it.

The goal is a public beta recommendation with accurate feature descriptions, install steps and
limits. Only the OnePlus 11R has documented device testing. Wider device testing, licensed
promotional assets and signing-key backup remain owner checks.

## Where things stand

v0.1.3 is a public release with an APK and SHA-256 asset. It reads CBZ, CBR (RAR4/RAR5), CBT,
PDF, EPUB and image folders, including SMB/FTP browsing. Classic ZIP encryption prompts for a
password. Cropping has a switch and leaves PDFs uncropped; colour correction, advanced upscaling
and background matching are optional and off by default. Komga/Kavita setup is hidden because
sync is not wired. Cloud sources and offline copies are not connected.

Main additionally has CB7/LZMA decoding, an Open-file button, reading-data JSON backup, clearer
empty states, scan safety and four reader fixes. These are **not in the v0.1.3 download**.
Large solid CB7 archives can page slowly; encrypted 7z remains unsupported.

## Findings

| # | Current status | Evidence | Still needed |
|---|---|---|---|
| F1 | Resolved: v0.1.3 is public, not draft or prerelease, with APK and checksum assets. | [Public release](https://github.com/therahul-yo/Absolutex/releases/tag/v0.1.3); GitHub releases/latest API checked 30 Sep 2026 | Repeat the download smoke check in a private browser before posting. |
| F2 | Resolved for launch copy: Komga/Kavita are not offered; sync is still inert. | `feature/remote/.../ServerKindOptions.kt:13`; `remote/sync/.../SyncController.kt:50-55` | Wire reader lifecycle calls before offering sync. |
| F3 | Partly resolved: Bug and File won't open issue forms exist. Settings About has no report link; a local crash-report flow is not verified. | `.github/ISSUE_TEMPLATE/bug.yml`, `file-wont-open.yml`; `feature/settings/.../SettingsControls.kt` | A Settings report link and any local crash-log design are separate work. |
| F4 | Partly resolved: README links to Obtainium and GitHub release notifications; no in-app update check. | README Updates; v0.1.3 release limitations | Store distribution and any in-app update UI are future work, not promises. |
| F5 | Open: README still contains copyrighted comic screenshots. | README screenshot block; `docs/screenshots/` | Lead replaces promotional assets with licensed material and checks them on a device. |
| F6 | Partly resolved: onboarding is merged; main adds Open file and honest empty states. A licensed sample comic is not attached to v0.1.3. | `app/.../MainActivity.kt:213-239`; v0.1.3 release assets | Obtain and credit a sample asset; do not claim main-only first-run UI in the current download. |
| F7 | Open: documented testing is only on OnePlus 11R. | DEVELOPMENT: Platform floor and Measured on the reference device; v0.1.3 release | Samsung, Pixel and other providers/devices remain unverified. The supported floor remains Android 13+, arm64, flagship-class Snapdragon. |
| F8 | Open: site uses ASCII artwork, with no separate install/privacy page or real app screenshots. | `site/index.html` | Any redesign and licensed image pass are separate work; current copy stays plain. |
| F9 | Addressed in this pass: README is user-facing; developer status/module map/roadmap now distinguish connected features from unfinished engines. | `docs/DEVELOPMENT.md`; `feature/reader/.../OpenBook.kt:41-89`; `feature/remote/.../BrowseViewModel.kt:167-181` | Recheck docs when the next release ships. |
| F10 | Resolved for the latest release: v0.1.3 has user-facing release notes. | [v0.1.3 notes](https://github.com/therahul-yo/Absolutex/releases/tag/v0.1.3) | Lead writes next-release notes; this docs PR changes no release records. |
| F11 | Resolved: crop can be disabled, defaults on, and never crops PDFs. | `core/data/.../settings/RenderingPrefs.kt:30-35`; v0.1.3 notes | Check problematic pages with crop on/off; no accuracy guarantee. |
| F12 | Partly resolved: classic ZIP encryption prompts; AES ZIP and encrypted 7z report unsupported encryption. Password-protected RAR/CBR remains untested. | `feature/reader/.../ReaderViewModel.kt:831-861`; v0.1.3 limitations | Ordinary CBR was used in the OnePlus benchmark; that does not verify encrypted CBR. |
| F13 | Open owner check: signing-key backup is not verified. Main now has manual reading-data backup; it excludes credentials, folder grants, text-EPUB within-chapter position and tap-guide state. | `app/src/main/AndroidManifest.xml` (`allowBackup=false`); DEVELOPMENT: Reading-data backup | Owner confirms offline signing-key backups. Publish a release containing JSON backup before recommending it to v0.1.3 users. |

The recorded OnePlus 11R benchmark missed two targets: 2 missed deadlines in 1,387 frames,
and median tap-to-first-page 329.8 ms against a 250 ms target. These are measurements, not a
smoothness guarantee. See DEVELOPMENT: Measured on the reference device.

## The plan

### Phase 0: before a public recommendation

- Public release, onboarding and hiding unwired sync are completed; repeat the published-APK download check.
- Owner confirms signing-key backups without sharing secrets.
- Owner/lead tests the signed release on additional phones. Do not describe untested devices as supported by testing.
- Replace copyrighted promotional screenshots with licensed assets before using them in a post or store listing.
- Lead reviews the claims table and shows the owner final website wording before merging this PR.

### Phase 1: first impression and trust

- Obtain a credited sample comic and licensed screenshots/video; no assets are changed by this text pass.
- Consider site install/privacy help separately; retain the current site's layout here.
- README structure is already user-facing, with development details in DEVELOPMENT.md.
- Crop switch and issue forms are shipped. A Settings report link is still separate work.

### Phase 2: launch-week feedback

- Use the existing issue forms for reproducible bugs and files that do not open.
- Any local crash-log or report-link feature needs a separate design and implementation.
- Keep unsupported-encryption messages and untested encrypted RAR limits explicit.

### Phase 3: after the post

- Obtainium is the documented external update path; there is no built-in update check.
- Store listings are owner work and require checking current store policies, licensed assets and privacy requirements.
- Reading-data JSON backup is merged on main, not yet released. It is manual export/import, not Android automatic backup.
- Remote browsing already fetches covers. Cloud sources, offline copies and progress sync remain unavailable.

## Pre-release smoke checklist

Use the **signed APK from the published release**. Record the version and phone for every check.

- [ ] Download through the site in a private mobile browser; record install warnings.
- [ ] Complete onboarding, pick a folder and confirm covers appear.
- [ ] Open CBZ, ordinary CBR, CBT, PDF and text EPUB; check first page/chapter.
- [ ] Turn pages, pinch/double-tap zoom, try RTL, rotate and check landscape spreads.
- [ ] Leave a book and reopen; check reading position.
- [ ] Open from a file manager and a downloaded file; check folder add/remove and rescanning.
- [ ] Browse/open SMB or FTP if available.
- [ ] Check dark/light modes and large text for clipping.
- [ ] Check corrupt files, ZipCrypto correct/wrong passwords and unsupported encryption messages.
- [ ] Install the next signed build **over the existing one without uninstalling**; verify data survives.
- [ ] When a new release contains main-only features, test CB7, backup/restore, Open file and read-failure preservation before moving them out of the Next release section.

## Release notes template

Use the actual release version, tested phones and supported features; do not copy main-only claims
into an older release. Include requirements (Android 13+, arm64, flagship-class Snapdragon),
install instructions, real APK/checksum assets, measured limits, encryption caveats and the
[issue forms](https://github.com/therahul-yo/Absolutex/issues/new/choose).
The lead publishes release records; this PR only changes repository text.

## Draft post

> Absolutex is an open-source beta comic and book reader for flagship Android phones.
> The v0.1.3 download reads CBZ, CBR, CBT, PDF, EPUB and image folders from your storage or
> SMB/FTP shares. It has RTL reading, spreads, tiled comic/PDF rendering and optional colour tools.
> Android 13+, arm64; tested only on a OnePlus 11R. No automatic updates; Obtainium can track releases.
> Known limits and installation: absolutex.vercel.app and the README.
>
> [Use a recording with a licensed comic; copyrighted screenshots are not cleared for promotion.]

## Definition of done: "confident to post"

- [ ] Published APK download checked in a private browser.
- [ ] First-run experience tested with and without existing comics; a sample is still future work.
- [x] Komga/Kavita setup hidden until sync is wired.
- [ ] Additional phones passed the smoke checklist.
- [ ] Promotional screenshots/video use licensed comics.
- [x] Bug and File won't open issue forms exist.
- [ ] Owner confirms signing-key backups in two offline places.
- [ ] Public claims match the published version; main-only features stay labelled until released.
