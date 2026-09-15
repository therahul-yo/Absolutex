# `:source:pdf` — native licence position

Pinned artifact: **PDFium 155.0.8044.0**, upstream tag `chromium/8044`, from
`bblanchon/pdfium-binaries`, file `pdfium-android-arm64.tgz`,
SHA256 `37686e64fa005484d619550a78805500de4c5a6f4df7aa06d8576145bfbee98f`.

**Verdict: clear.** No GPL, LGPL or AGPL code is compiled into or linked by `libpdfium.so`.
Every component is permissive (BSD / MIT / Apache-2.0 / Zlib / FTL / Unicode), and all of
them are attribution-only — none is copyleft, and none imposes a condition on the source of
the application that links it.

## Inventory

Taken from the `licenses/` directory of the pinned archive, which is reproduced verbatim
under `src/main/assets/licenses/pdfium/`.

| Component | Licence | SPDX |
|---|---|---|
| PDFium | BSD 3-Clause | `BSD-3-Clause` |
| Abseil | Apache 2.0 | `Apache-2.0` |
| Anti-Grain Geometry 2.3 | Permissive custom (copy/use/modify/sell granted, notice retained) | — |
| Catapult | BSD 3-Clause | `BSD-3-Clause` |
| cpu_features | Apache 2.0 | `Apache-2.0` |
| fast_float | MIT | `MIT` |
| FreeType | FreeType Project License | `FTL` |
| ICU | Unicode License v3 | `Unicode-3.0` |
| Little CMS (lcms2) | MIT | `MIT` |
| libjpeg-turbo | IJG + BSD 3-Clause | `IJG AND BSD-3-Clause` |
| OpenJPEG | BSD 2-Clause | `BSD-2-Clause` |
| libpng | PNG Reference Library License v2 | `libpng-2.0` |
| libunwind (LLVM) | Apache 2.0 with LLVM exception | `Apache-2.0 WITH LLVM-exception` |
| llvm-libc | Apache 2.0 with LLVM exception | `Apache-2.0 WITH LLVM-exception` |
| simdutf | MIT | `MIT` |
| zlib | zlib | `Zlib` |
| pdfium-binaries (packaging only) | MIT | `MIT` |

## Two findings worth recording

**1. The GPL text in `icu.txt` is not a problem, and here is why.**
A naive `grep -i gpl` over `licenses/` hits three files. Two of them — `llvm-libc.txt` and
`libunwind.txt` — match only the *LLVM exception*, whose whole purpose is to grant GPLv2
compatibility; the code itself is Apache-2.0. The third, `icu.txt`, carries real GPL text,
but it is scoped in the file to exactly three paths: `aclocal.m4`, `config.guess` and
`install-sh`. Those are Autoconf build plumbing, they each carry the standard Autoconf
exception, and PDFium does not build ICU with Autoconf at all — it builds it with GN. None
of the three is compiled, linked or shipped. No GPL code reaches the APK.

**2. FreeType is bundled under FTL, not GPLv2.**
FreeType is dual-licensed FTL / GPLv2 and the *licensee* elects one. The archive ships
`FTL.TXT` only, so the election is FTL: BSD-style, attribution-only.

## What we are actually obliged to do

Every licence here is attribution-only, and between them BSD-3-Clause, Apache-2.0, MIT and
FTL all require that the licence text travel with a binary distribution. So the texts are
vendored into `src/main/assets/licenses/pdfium/` and ship inside the APK.

### 🚩 Release gate: shipping the texts is NOT sufficient on its own

Two components additionally require a *positive statement* in the product's documentation,
which no amount of licence text in `assets/` satisfies. Both are triggered specifically by
distributing **binary only**, which is exactly what we do — we ship a prebuilt `libpdfium.so`
and no PDFium source.

- **libjpeg-turbo / IJG** (`libjpeg_turbo.ijg`, clause 2): *"If only executable code is
  distributed, then the accompanying documentation must state that 'this software is based in
  part on the work of the Independent JPEG Group'."*
- **FreeType** (`freetype.txt`, FTL redistribution terms): *"Redistribution in binary form
  must provide a disclaimer that states that the software is based in part of the work of the
  FreeType Team, in the distribution documentation."*

So before any release ships this module, the app's about/licences surface must carry both
sentences verbatim. Suggested wording, covering both in one place:

> This software is based in part on the work of the Independent JPEG Group, and in part on
> the work of the FreeType Team (https://freetype.org).

> **TODO(app):** this is a **release blocker**, not a nicety, and it is not satisfied by the
> `assets/` files alone. It belongs with the attribution screen below; whoever builds that
> screen owns both.

> **TODO(app):** `:app` has no attribution screen yet. Shipping the files in `assets/` meets
> the "reproduce the notice" requirement, but they should be *reachable* from the UI — a
> Settings → Open-source licences screen listing this directory plus `:source:libarchive`'s
> libarchive notice. That belongs to the `:app` module, which this lane does not own.

Apache-2.0 §4(d) additionally requires propagating a `NOTICE` file if the upstream has one.
Neither the pinned archive nor Abseil, cpu_features, llvm-libc or libunwind ships a `NOTICE`
in this bundle, so there is nothing to propagate.

## Not an Apache-2.0 wrapper

The JNI wrapper in `src/main/cpp/pdfium_jni.c` is written for this project. No third-party
Android/JNI PDFium wrapper is vendored — the brief rules those out, and the widely used ones
are Apache-2.0 forks of an abandoned original with no current maintainer.

## Incidental security note

`args.gn` in the pinned archive shows the build was configured with `pdf_enable_v8 = false`
and `pdf_enable_xfa = false`. There is no JavaScript engine and no XFA forms engine in this
binary, which removes the largest PDF attack surface by some distance. Keep both off when
re-pinning.

## Re-pinning

Run `tools/refresh-pdfium.sh <release-tag>`. It re-downloads the artifact, prints the SHA256
to paste into `src/main/cpp/CMakeLists.txt`, refreshes the vendored licence texts, and
re-runs the GPL scan so a version bump cannot quietly change the licence position.
