#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the Absolutex hostile test corpus (spec section 8).

This is a *generator*, not a bundle of binaries: nothing here is committed as an
artifact, so the repository stays small and no third-party sample ends up under a
licence we have not cleared.

Determinism
-----------
Everything this script emits from pure Python is byte-for-byte reproducible on any
machine and any CPython 3.9+:

  * the PRNG is an explicit xorshift64* seeded from ``SEED`` -- we do not rely on
    ``random``, whose stream is only guaranteed stable for a subset of methods;
  * every ZIP entry gets a fixed timestamp, fixed ``create_system`` and fixed
    ``external_attr``, so no host metadata leaks into the archive;
  * zlib runs at a pinned compression level.

``tools/corpus-expected-sha256.json`` pins the digests of the always-generated,
pure-Python cases. ``--check`` re-generates and compares against it, which is how
CI notices that a "harmless" refactor quietly changed the corpus.

Cases that need an external encoder or archiver (RAR5, solid 7z, AVIF) are
detected at run time and **skipped with an explanation**. In particular RAR5
needs RARLAB's ``rar``, which is proprietary -- we will not vendor it, and the
corpus is expected to be incomplete on a machine that lacks it.

Usage
-----
    python3 tools/make-corpus.py --out build/corpus
    python3 tools/make-corpus.py --out build/corpus --include-huge
    python3 tools/make-corpus.py --out build/corpus --check
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import tarfile
import sys
import zipfile
import zlib
from pathlib import Path
from typing import Callable, Iterable, Iterator

# --------------------------------------------------------------------------------------
# Determinism knobs
# --------------------------------------------------------------------------------------

SEED = 0x5AB5_01E7_0BADC0DE
ZIP_EPOCH = (2001, 1, 1, 0, 0, 0)   # fixed DOS timestamp on every entry
ZLIB_LEVEL = 6                       # pinned: the default has changed across zlib builds
UNIX_MODE = 0o644 << 16              # fixed external_attr; host umask must not leak in
CREATE_SYSTEM_UNIX = 3               # zipfile picks 0 on Windows and 3 elsewhere

# Page geometry. 1240 wide matches the reference panel's short edge exactly, so a
# FIT_WIDTH render on the OnePlus 11R is a 1:1 blit and any resampling shows up as a
# regression rather than hiding inside a scale factor.
PAGE_W, PAGE_H = 1240, 1754
GIANT_EDGE = 12000                   # 144 MP, the section 8 single-page stress case
HUGE_TARGET = (2 << 30) + (100 << 20)  # ~2.1 GiB: pushes entry offsets past 2^31


class Rng:
    """xorshift64*, written out so the stream cannot drift with the Python version."""

    __slots__ = ("s",)

    def __init__(self, seed: int) -> None:
        self.s = (seed & 0xFFFF_FFFF_FFFF_FFFF) or 0x9E3779B97F4A7C15

    def next64(self) -> int:
        x = self.s
        x ^= (x << 13) & 0xFFFF_FFFF_FFFF_FFFF
        x ^= x >> 7
        x ^= (x << 17) & 0xFFFF_FFFF_FFFF_FFFF
        self.s = x
        return (x * 0x2545F4914F6CDD1D) & 0xFFFF_FFFF_FFFF_FFFF

    def block(self, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            out += self.next64().to_bytes(8, "little")
        return bytes(out[:n])


# --------------------------------------------------------------------------------------
# PNG encoding (stdlib only -- Pillow would make output depend on its own version)
# --------------------------------------------------------------------------------------

def _chunk(tag: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFF_FFFF))


def write_png(fp, width: int, height: int, rows: Iterable[bytes], colour: int) -> None:
    """Stream a PNG out row by row.

    Streaming rather than buffering is what makes the 12000x12000 case cheap: we never
    hold the 144 MB of raw samples, only one scanline and zlib's window.
    ``colour`` is the PNG colour type: 0 = greyscale, 2 = truecolour.
    """
    fp.write(b"\x89PNG\r\n\x1a\n")
    fp.write(_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, colour, 0, 0, 0)))
    comp = zlib.compressobj(ZLIB_LEVEL)
    payload = bytearray()
    for row in rows:
        payload += comp.compress(b"\x00" + row)   # filter type 0; the patterns below
        if len(payload) >= (1 << 20):             # gain nothing from adaptive filtering
            fp.write(_chunk(b"IDAT", bytes(payload)))
            payload.clear()
    payload += comp.flush()
    if payload:
        fp.write(_chunk(b"IDAT", bytes(payload)))
    fp.write(_chunk(b"IEND", b""))


def page_png(index: int, width: int = PAGE_W, height: int = PAGE_H) -> bytes:
    """A small RGB page whose banding encodes its index, so a mis-ordered read is visible."""
    import io

    base_r = (index * 37) & 0xFF
    base_g = (index * 91) & 0xFF
    base_b = (index * 149) & 0xFF
    bar_h = max(1, height // 24)

    def rows() -> Iterator[bytes]:
        for y in range(height):
            # Every (index+1)-th band flips to inverse: counting the bands in a viewer
            # recovers the page number without decoding any text.
            dark = ((y // bar_h) % (index + 2)) == 0
            if dark:
                px = bytes((255 - base_r, 255 - base_g, 255 - base_b))
            else:
                px = bytes((base_r, base_g, base_b))
            yield px * width

    buf = io.BytesIO()
    write_png(buf, width, height, rows(), colour=2)
    return buf.getvalue()


def giant_png(path: Path, edge: int = GIANT_EDGE) -> None:
    """One 12000x12000 greyscale page, written straight to disk.

    The pattern is a diagonal ramp built with ``bytes.translate`` so each scanline costs
    one C-level pass over ``edge`` bytes instead of a 144-million-iteration Python loop.
    It compresses well, which is deliberate: the stress this case applies is the 144 MP
    *decoded* surface against the memory budget, not the file size.
    """
    base = bytes(((x >> 5) & 0xFF) for x in range(edge))

    def rows() -> Iterator[bytes]:
        for y in range(edge):
            shift = (y >> 5) & 0xFF
            table = bytes(((i + shift) & 0xFF) for i in range(256))
            yield base.translate(table)

    with path.open("wb") as fp:
        write_png(fp, edge, edge, rows(), colour=0)


# --------------------------------------------------------------------------------------
# Deterministic ZIP
# --------------------------------------------------------------------------------------

def zinfo(name: str, compress: int = zipfile.ZIP_DEFLATED) -> zipfile.ZipInfo:
    zi = zipfile.ZipInfo(name, date_time=ZIP_EPOCH)
    zi.compress_type = compress
    zi.create_system = CREATE_SYSTEM_UNIX
    zi.external_attr = UNIX_MODE
    return zi


COMIC_INFO = """<?xml version="1.0" encoding="utf-8"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <Title>Absolutex Corpus Volume</Title>
  <Series>Absolutex Corpus</Series>
  <Number>1</Number>
  <PageCount>{count}</PageCount>
  <LanguageISO>en</LanguageISO>
  <Manga>{manga}</Manga>
</ComicInfo>
"""


def build_cbz(path: Path,
              entries: list[tuple[str, bytes]],
              comic_info: str | None = None,
              compress: int = zipfile.ZIP_DEFLATED) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", allowZip64=True) as zf:
        if comic_info is not None:
            zf.writestr(zinfo("ComicInfo.xml"), comic_info)
        for name, blob in entries:
            zf.writestr(zinfo(name, compress), blob)


# --------------------------------------------------------------------------------------
# Minimal PDF with an outline (stdlib only)
# --------------------------------------------------------------------------------------

def _pdf_text(s: str) -> bytes:
    """A PDF string literal: ASCII stays literal, anything else becomes UTF-16BE hex."""
    try:
        raw = s.encode("ascii")
    except UnicodeEncodeError:
        return b"<" + (b"\xfe\xff" + s.encode("utf-16-be")).hex().upper().encode() + b">"
    return b"(" + raw.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)") + b")"


def build_pdf_with_outline(path: Path) -> None:
    """Three pages under a three-entry /Outlines tree, one title non-ASCII.

    Hand-rolled because every Python PDF library stamps a creation date or producer
    string into the file, which would break byte-for-byte reproducibility.
    """
    titles = ["Chapter 1", "Chapter 2", "第三章"]  # third title is CJK
    page_obj = [4, 6, 8]
    item_obj = [10, 11, 12]

    objs: dict[int, bytes] = {
        1: b"<< /Type /Catalog /Pages 2 0 R /Outlines 3 0 R /PageMode /UseOutlines >>",
        2: b"<< /Type /Pages /Kids [4 0 R 6 0 R 8 0 R] /Count 3 >>",
        3: b"<< /Type /Outlines /First 10 0 R /Last 12 0 R /Count 3 >>",
        13: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    }

    for i, (pobj, title) in enumerate(zip(page_obj, titles)):
        stream = ("BT /F1 36 Tf 72 700 Td (Corpus page %d) Tj ET\n"
                  "1 0 0 RG 6 w 72 120 m 540 120 l S\n" % (i + 1)).encode("ascii")
        objs[pobj] = (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                      b"/Resources << /Font << /F1 13 0 R >> >> /Contents %d 0 R >>" % (pobj + 1))
        objs[pobj + 1] = (b"<< /Length %d >>\nstream\n" % len(stream)) + stream + b"endstream"

    for i, (iobj, pobj, title) in enumerate(zip(item_obj, page_obj, titles)):
        parts = [b"<< /Title ", _pdf_text(title), b" /Parent 3 0 R"]
        if i > 0:
            parts.append(b" /Prev %d 0 R" % item_obj[i - 1])
        if i < len(item_obj) - 1:
            parts.append(b" /Next %d 0 R" % item_obj[i + 1])
        parts.append(b" /Dest [%d 0 R /Fit] >>" % pobj)
        objs[iobj] = b"".join(parts)

    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets: dict[int, int] = {}
    for num in sorted(objs):
        offsets[num] = len(out)
        out += b"%d 0 obj\n" % num + objs[num] + b"\nendobj\n"

    xref_at = len(out)
    top = max(objs) + 1
    out += b"xref\n0 %d\n" % top
    out += b"0000000000 65535 f \n"
    for num in range(1, top):
        out += b"%010d 00000 n \n" % offsets.get(num, 0)
    # Fixed /ID: a generated one would be random and break reproducibility.
    out += (b"trailer\n<< /Size %d /Root 1 0 R /ID [<%s> <%s>] >>\nstartxref\n%d\n%%%%EOF\n"
            % (top, b"A" * 32, b"A" * 32, xref_at))

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(out))


# --------------------------------------------------------------------------------------
# External tool discovery
# --------------------------------------------------------------------------------------

def which(*names: str) -> str | None:
    for n in names:
        p = shutil.which(n)
        if p:
            return p
    return None


SKIPS: list[tuple[str, str]] = []


def skip(case: str, why: str) -> None:
    SKIPS.append((case, why))
    print("  SKIP  %-28s %s" % (case, why))


# --------------------------------------------------------------------------------------
# The cases
# --------------------------------------------------------------------------------------

def pages(n: int, start: int = 0) -> list[tuple[str, bytes]]:
    return [("page%03d.png" % (i + 1), page_png(start + i)) for i in range(n)]


def case_basic(out: Path) -> None:
    """Baseline: well-formed, ComicInfo.xml present. Everything else is measured against it."""
    build_cbz(out / "01_basic_ltr.cbz", pages(12),
              comic_info=COMIC_INFO.format(count=12, manga="No"))


def case_no_comicinfo(out: Path) -> None:
    """Same bytes minus the metadata sidecar: page order must fall back to natural sort."""
    build_cbz(out / "02_no_comicinfo.cbz", pages(12), comic_info=None)


def case_mixed_case_ext(out: Path) -> None:
    """Extension matching has to be case-insensitive; real-world archives are not tidy."""
    for name in ("03_upper.CBZ", "04_mixed.CbZ", "05_upper.CB7"):
        build_cbz(out / name, pages(4), comic_info=COMIC_INFO.format(count=4, manga="No"))
    # Extension lies about the container: a ZIP wearing a .cbr suffix. Sniffing must win
    # over the filename. (Not in the section 8 list -- added because it costs one line and
    # is the single most common real-world malformation.)
    build_cbz(out / "06_zip_named_cbr.cbr", pages(4), comic_info=None)


def case_deep_nesting(out: Path) -> None:
    """Pages buried eight directories deep and split across siblings."""
    entries: list[tuple[str, bytes]] = []
    deep = "/".join("level%02d" % d for d in range(1, 9))
    for i in range(6):
        entries.append(("%s/chapter_a/page%03d.png" % (deep, i + 1), page_png(i)))
    for i in range(6):
        entries.append(("%s/chapter_b/page%03d.png" % (deep, i + 1), page_png(6 + i)))
    build_cbz(out / "07_deep_nesting.cbz", entries, comic_info=None)


def case_nonascii_rtl(out: Path) -> None:
    """Non-ASCII, RTL, combining marks and a bidi override in entry names.

    U+202E RIGHT-TO-LEFT OVERRIDE is the hostile one: rendered naively it reverses the
    visible filename, which is the classic extension-spoofing trick. The reader must
    neutralise it for display without altering the name it uses to open the entry.
    """
    entries = [
        ("ページ_001.png", page_png(0)),                  # Japanese
        ("صفحة_002.png", page_png(1)),            # Arabic (RTL)
        ("עמוד_003.png", page_png(2)),            # Hebrew (RTL)
        ("pagé_004.png", page_png(3)),                          # combining acute
        ("\U0001f4d6_005.png", page_png(4)),                          # emoji, non-BMP
        ("page‮006gnp.png", page_png(5)),                        # bidi override
        ("éèê_007.png", page_png(6)),                  # Latin-1 accents
    ]
    build_cbz(out / "08_nonascii_rtl.cbz", entries,
              comic_info=COMIC_INFO.format(count=7, manga="YesAndRightToLeft"))


def case_two_pages(out: Path) -> None:
    """Exactly two pages: the degenerate case for any prefetch window wider than one."""
    build_cbz(out / "09_two_pages.cbz", pages(2),
              comic_info=COMIC_INFO.format(count=2, manga="No"))


def case_junk_entries(out: Path) -> None:
    """Archiver litter that must never surface as a page."""
    entries: list[tuple[str, bytes]] = []
    entries += [("__MACOSX/._page%03d.png" % (i + 1), b"\x00\x05\x16\x07" + b"\x00" * 60)
                for i in range(3)]
    entries.append(("Thumbs.db", b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 120))
    entries.append((".DS_Store", b"\x00\x00\x00\x01Bud1" + b"\x00" * 100))
    entries.append(("desktop.ini", b"[.ShellClassInfo]\r\nIconResource=x\r\n"))
    entries.append(("cover_thumb.db", b"not an image"))
    entries.append(("empty_page.png", b""))            # zero-byte, image extension
    entries.append(("notes/readme.txt", b"not a page\n"))
    entries += pages(8)
    build_cbz(out / "10_junk_entries.cbz", entries, comic_info=None)


def case_truncated(out: Path) -> None:
    """A valid CBZ cut off at 62 percent.

    Truncation always destroys the central directory, so the reader has to recover pages
    by walking local file headers. Spec section 2: degrade to readablePageCount, never crash.
    """
    src = out / ".tmp_truncate.cbz"
    build_cbz(src, pages(20), comic_info=COMIC_INFO.format(count=20, manga="No"))
    blob = src.read_bytes()
    src.unlink()
    (out / "11_truncated.cbz").write_bytes(blob[: (len(blob) * 62) // 100])


def case_giant_page(out: Path) -> None:
    """One 12000x12000 page (144 MP decoded) alongside three ordinary ones."""
    tmp = out / ".tmp_giant.png"
    giant_png(tmp)
    entries = [("page001.png", page_png(0)),
               ("page002_giant.png", tmp.read_bytes()),
               ("page003.png", page_png(2)),
               ("page004.png", page_png(3))]
    tmp.unlink()
    build_cbz(out / "12_giant_page.cbz", entries, comic_info=None)


def case_pdf_outline(out: Path) -> None:
    """A PDF carrying a real /Outlines tree, one entry titled in CJK."""
    build_pdf_with_outline(out / "13_outline.pdf")


def case_solid_7z(out: Path) -> None:
    """Solid 7z, which defeats random access: every page before N must be inflated to reach N.

    Note for whoever wires this up: the native build currently sets ENABLE_LZMA=OFF
    (see source/libarchive/src/main/cpp/CMakeLists.txt, TODO(phase6)), so .cb7 will not
    open yet. This case exists so phase 6 has something to open on day one.
    """
    exe = which("7zz", "7z", "7za")
    if not exe:
        skip("14_solid.cb7", "no 7z binary on PATH (install p7zip / 7-Zip; LGPL, fine to require)")
        return
    staging = out / ".tmp_7z"
    staging.mkdir(parents=True, exist_ok=True)
    for name, blob in pages(10):
        (staging / name).write_bytes(blob)
    (staging / "ComicInfo.xml").write_text(COMIC_INFO.format(count=10, manga="No"), "utf-8")
    target = out / "14_solid.cb7"
    target.unlink(missing_ok=True)
    # -ms=on forces one solid block; -mx=9 keeps it genuinely expensive to seek into.
    proc = subprocess.run([exe, "a", "-t7z", "-ms=on", "-mx=9", "-bso0", "-bsp0",
                           str(target), "."],
                          cwd=staging, capture_output=True)
    shutil.rmtree(staging)
    if proc.returncode != 0:
        target.unlink(missing_ok=True)
        skip("14_solid.cb7", "7z exited %d: %s"
             % (proc.returncode, proc.stderr.decode("utf-8", "replace").strip()[:160]))


def case_rar5(out: Path) -> None:
    """RAR5.

    Requires RARLAB's ``rar``, which is proprietary shareware. We will not vendor it and
    we will not check in a sample archive, so this case is simply absent unless the
    developer has installed ``rar`` themselves. ``unrar``/``unar`` cannot help -- they
    only extract. libarchive reads RAR5 through a clean-room implementation, so the
    *reader* side carries no licence problem; only corpus generation does.
    """
    exe = which("rar")
    if not exe:
        skip("15_rar5.cbr", "no 'rar' binary on PATH (proprietary, deliberately not vendored)")
        return
    staging = out / ".tmp_rar"
    staging.mkdir(parents=True, exist_ok=True)
    for name, blob in pages(8):
        (staging / name).write_bytes(blob)
    target = out / "15_rar5.cbr"
    target.unlink(missing_ok=True)
    proc = subprocess.run([exe, "a", "-ma5", "-m3", "-ep1", "-idq", str(target), "."],
                          cwd=staging, capture_output=True)
    shutil.rmtree(staging)
    if proc.returncode != 0:
        target.unlink(missing_ok=True)
        skip("15_rar5.cbr", "rar exited %d" % proc.returncode)


def case_avif(out: Path) -> None:
    """AVIF pages inside a CBZ: the format a modern scanner actually emits."""
    exe = which("avifenc")
    if not exe:
        skip("16_avif.cbz", "no 'avifenc' on PATH (libavif, BSD-2 -- fine to require, not vendored)")
        return
    staging = out / ".tmp_avif"
    staging.mkdir(parents=True, exist_ok=True)
    entries: list[tuple[str, bytes]] = []
    for i in range(6):
        src = staging / ("src%03d.png" % i)
        dst = staging / ("page%03d.avif" % (i + 1))
        src.write_bytes(page_png(i))
        proc = subprocess.run([exe, "-s", "6", "-q", "70", str(src), str(dst)],
                              capture_output=True)
        if proc.returncode != 0 or not dst.exists():
            shutil.rmtree(staging)
            skip("16_avif.cbz", "avifenc exited %d" % proc.returncode)
            return
        entries.append((dst.name, dst.read_bytes()))
    shutil.rmtree(staging)
    build_cbz(out / "16_avif.cbz", entries, comic_info=None)


def case_comicinfo_deep(out: Path) -> None:
    """ComicInfo.xml at a NON-ZERO raw ordinal, behind junk and with a nested casing variant.

    The locator walks the RAW entry list and must extract by archive ordinal, not by filtered
    page index: junk entries occupy ordinal slots the page filter drops. build_cbz always
    writes the sidecar first (ordinal 0), so this case writes its own zip with the sidecar
    LAST: pages, then junk, then a nested mixed-case ComicInfo.xml, so its raw ordinal sits
    behind everything the filter rejects.
    """
    entries = pages(6)
    entries += [("__MACOSX/._page001.png", b"\x00\x05\x16\x07" + b"\x00" * 40),
                ("Thumbs.db", b"\xd0\xcf\x11\xe0" + b"\x00" * 80),
                # Nested and mixed-case: real re-zippers emit this; ComicRack's root-only
                # assumption would miss it.
                ("scan/sub/COMICINFO.XML", COMIC_INFO.format(count=6, manga="No").encode("utf-8"))]
    path = out / "18_comicinfo_behind_junk.cbz"
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", allowZip64=True) as zf:
        for name, blob in entries:
            zf.writestr(zinfo(name), blob)


def _epub_bytes(rtl: bool = False) -> bytes:
    """A conforming fixed-layout EPUB, in memory.

    OCF requires the ``mimetype`` entry first and STORED, which is the whole reason an EPUB is
    identifiable from its first 58 bytes. Written here with that shape on purpose, so a sniffer
    that skips the compression method or the entry order is caught rather than flattered.

    Kindle Comic Creator's markup: the page wrapped in <svg><image xlink:href> so it scales to
    the viewport. The href climbs out of text/ into img/, which is the path-resolution case a
    reader gets wrong first.
    """
    import io

    direction = ' page-progression-direction="rtl"' if rtl else ""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", allowZip64=True) as zf:
        zf.writestr(zinfo("mimetype", zipfile.ZIP_STORED), b"application/epub+zip")
        zf.writestr(zinfo("META-INF/container.xml"),
                    '<?xml version="1.0"?>\n'
                    '<container version="1.0" '
                    'xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                    '<rootfiles><rootfile full-path="OEBPS/content.opf" '
                    'media-type="application/oebps-package+xml"/></rootfiles></container>')
        items, refs = [], []
        for i in range(1, 5):
            items.append('<item id="p%d" href="text/p%d.xhtml" media-type="application/xhtml+xml"/>'
                         '<item id="i%d" href="img/p%d.png" media-type="image/png"/>' % (i, i, i, i))
            refs.append('<itemref idref="p%d"/>' % i)
        zf.writestr(zinfo("OEBPS/content.opf"),
                    '<?xml version="1.0" encoding="utf-8"?>\n'
                    '<package xmlns="http://www.idpf.org/2007/opf" version="3.0">'
                    '<metadata/><manifest>%s</manifest><spine%s>%s</spine></package>'
                    % ("".join(items), direction, "".join(refs)))
        for i in range(1, 5):
            zf.writestr(zinfo("OEBPS/text/p%d.xhtml" % i),
                        '<html xmlns:xlink="http://www.w3.org/1999/xlink"><body>'
                        '<svg viewBox="0 0 1240 1754"><image xlink:href="../img/p%d.png"/></svg>'
                        '</body></html>' % i)
            zf.writestr(zinfo("OEBPS/img/p%d.png" % i), page_png(i - 1))
    return buf.getvalue()


def case_epub(out: Path) -> None:
    """A fixed-layout EPUB, and the same bytes wearing a .cbz suffix.

    The pair is the point: they are byte-identical, so anything that tells them apart is reading
    the name rather than the content. The RTL variant carries
    ``page-progression-direction="rtl"``, which is how a manga EPUB says which way it reads.
    """
    (out / "19_fixed_layout.epub").write_bytes(_epub_bytes())
    (out / "20_epub_named_cbz.cbz").write_bytes(_epub_bytes())
    (out / "21_epub_rtl.epub").write_bytes(_epub_bytes(rtl=True))


def case_tar(out: Path) -> None:
    """A real TAR wearing .cbt, whose magic sits at offset 257 rather than at the start.

    Every field that would otherwise carry this machine's identity is pinned, because tarfile
    defaults to the current uid/gid/mtime and would make the corpus irreproducible.
    """
    import io

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tf:
        for i in range(1, 5):
            blob = page_png(i - 1)
            info = tarfile.TarInfo("page%03d.png" % i)
            info.size = len(blob)
            info.mtime = 978307200            # 2001-01-01T00:00:00Z, matching ZIP_EPOCH
            info.mode = 0o644
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            tf.addfile(info, io.BytesIO(blob))
    (out / "22_tar.cbt").write_bytes(buf.getvalue())


def case_cbz_holding_a_pdf(out: Path) -> None:
    """A CBZ that stores a real PDF, which is the misroute case §6's sniffer exists to fix.

    "%PDF-" lands inside the first kibibyte, so a reader that searches for the PDF magic before
    establishing the container hands a ZIP to the PDF engine and the book fails to open. STORED,
    so the magic really is in those bytes rather than behind a deflate stream.
    """
    # try/finally, not unlink-on-success: if build_pdf_with_outline throws, a stray file is
    # left in the output directory where a later --check finds something it did not generate.
    # (Review note on #79. case_truncated and case_giant_page have the same shape; left alone
    # here so this change stays the sidecar cases plus the one fix that was asked for.)
    pdf = out / ".tmp_bonus.pdf"
    try:
        build_pdf_with_outline(pdf)
        entries = [("bonus.pdf", pdf.read_bytes())] + pages(4)
    finally:
        pdf.unlink(missing_ok=True)
    path = out / "23_cbz_with_pdf_inside.cbz"
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", allowZip64=True) as zf:
        for name, blob in entries:
            zf.writestr(zinfo(name, zipfile.ZIP_STORED), blob)


def case_comicinfo_malformed(out: Path) -> None:
    """A sidecar that is not valid XML. The metadata is lost; the book is not.

    `01_basic_ltr.cbz` already covers a well-formed sidecar at the archive root, and
    `18_comicinfo_behind_junk.cbz` covers a nested, mixed-case one at a non-zero raw ordinal.
    What none of them covered is a sidecar the parser cannot read, which is the whole of
    ComicInfoParser's degrade-never-crash contract.
    """
    entries = pages(6)
    build_cbz(out / "24_comicinfo_malformed.cbz", entries,
              comic_info='<?xml version="1.0"?>\n<ComicInfo><Series>Truncated at the')


def case_comicinfo_oversized(out: Path) -> None:
    """A sidecar past the 1 MiB parser cap, compressing to almost nothing.

    This is the shape the cap exists for: a few KB on disk that inflates into a DOM large
    enough to exhaust memory. OutOfMemoryError is an Error, not an Exception, so it escapes
    the "return null, never throw" contract entirely — refusing the input is the only reliable
    answer, and this case is how a reader that stops refusing gets caught.
    """
    filler = "<Notes>%s</Notes>" % ("A" * 64)
    oversized = ('<?xml version="1.0"?>\n<ComicInfo><Series>Oversized</Series>%s</ComicInfo>'
                 % (filler * 20000))
    assert len(oversized) > 1024 * 1024, len(oversized)
    build_cbz(out / "25_comicinfo_oversized.cbz", pages(6), comic_info=oversized)


def case_comicinfo_corrupt_entry(out: Path) -> None:
    """A sidecar whose compressed bytes are corrupt, so extracting it FAILS.

    The three cases above all reach the parser. This one does not: libarchive cannot inflate
    the entry at all, so the loader's extract step is what fails rather than the parse.

    That distinction is the point. It is the path a reader takes when the sidecar is damaged
    rather than merely wrong, it runs on every open because the sidecar is always looked for,
    and a loader that turns that failure into a thrown exception stops the book opening at all
    — pages that are perfectly readable included. Keeping the case in the corpus rather than in
    one PR's test file means every format path is held to it, not just the one being changed
    the day it was written.
    """
    name = "ComicInfo.xml"
    good = out / ".tmp_corrupt_sidecar.cbz"
    try:
        build_cbz(good, pages(6), comic_info=COMIC_INFO.format(count=6, manga="No"))
        blob = bytearray(good.read_bytes())
    finally:
        good.unlink(missing_ok=True)

    # The sidecar is written first, so its local header sits at offset 0 and its deflate
    # stream begins right after the name. Corrupting bytes inside that stream leaves every
    # other entry, and the central directory, perfectly intact.
    data_at = 30 + len(name)
    assert blob[:4] == b"PK\x03\x04", blob[:4]
    assert bytes(blob[30:data_at]) == name.encode("ascii"), bytes(blob[30:data_at])
    for i in range(data_at + 4, data_at + 24):
        blob[i] ^= 0xFF
    (out / "26_comicinfo_corrupt_entry.cbz").write_bytes(bytes(blob))


def case_comicinfo_two_sidecars(out: Path) -> None:
    """A root sidecar and a nested one, disagreeing on purpose.

    The loader takes the FIRST match in raw archive order, so which one wins is a property of
    the entry order rather than of the path depth. Two writers really do leave both behind —
    the original at the root, a re-zipper's copy under its own folder — and without a case the
    tie-break is only an implementation detail nobody chose.
    """
    root = COMIC_INFO.format(count=6, manga="No").replace(
        "<Series>Absolutex Corpus</Series>", "<Series>Root sidecar wins</Series>")
    nested = COMIC_INFO.format(count=99, manga="YesAndRightToLeft").replace(
        "<Series>Absolutex Corpus</Series>", "<Series>Nested sidecar loses</Series>")
    entries = pages(6) + [("scan/ComicInfo.xml", nested.encode("utf-8"))]
    build_cbz(out / "27_comicinfo_two_sidecars.cbz", entries, comic_info=root)


def case_huge(out: Path) -> None:
    """~2.1 GiB CBZ with real pages on both sides of the 2^31 byte offset.

    2 GiB is the interesting number, not 4: an entry offset that survives as a signed
    32-bit int reads fine below it and returns garbage above it. Pages 5-8 sit past the
    boundary, so a truncated-to-int offset fails loudly instead of subtly.

    The bulk is STORED filler with a .bin extension -- it costs no CPU to produce, and
    EntryFilter rejecting it is itself worth asserting.
    """
    chunk_total = 256 << 20
    chunks = -(-HUGE_TARGET // chunk_total)      # ceil: filler is written whole-chunk
    need = chunks * chunk_total + (64 << 20)
    free = shutil.disk_usage(out).free
    if free < need:
        skip("17_huge_2gb.cbz", "needs %.1f GiB free, have %.1f GiB"
             % (need / (1 << 30), free / (1 << 30)))
        return

    rng = Rng(SEED ^ 0x1234_5678)
    block = rng.block(1 << 20)          # 1 MiB, reused: STORED means repetition is free
    target = out / "17_huge_2gb.cbz"
    target.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(target, "w", allowZip64=True) as zf:
        zf.writestr(zinfo("ComicInfo.xml"), COMIC_INFO.format(count=8, manga="No"))
        for name, blob in pages(4):
            zf.writestr(zinfo(name), blob)
        for idx in range(chunks):
            zi = zinfo("filler/blob%02d.bin" % idx, zipfile.ZIP_STORED)
            with zf.open(zi, "w", force_zip64=True) as fh:
                for _ in range(chunk_total >> 20):
                    fh.write(block)
        for name, blob in pages(4, start=4):
            zf.writestr(zinfo(name.replace("page", "tail")), blob)


# --------------------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------------------

# (case id, builder, reproducible). "reproducible" means pure Python and always emitted,
# so its digest belongs in corpus-expected-sha256.json. External encoders vary between
# versions, and the huge case is opt-in, so neither is pinned.
CASES: list[tuple[str, Callable[[Path], None], bool]] = [
    ("basic",         case_basic,          True),
    ("no_comicinfo",  case_no_comicinfo,   True),
    ("mixed_case",    case_mixed_case_ext, True),
    ("deep_nesting",  case_deep_nesting,   True),
    ("nonascii_rtl",  case_nonascii_rtl,   True),
    ("two_pages",     case_two_pages,      True),
    ("junk_entries",  case_junk_entries,   True),
    ("truncated",     case_truncated,      True),
    ("giant_page",     case_giant_page,     True),
    ("comicinfo_deep", case_comicinfo_deep, True),
    ("pdf_outline",    case_pdf_outline,    True),
    ("epub",           case_epub,           True),
    ("tar",            case_tar,            True),
    ("cbz_with_pdf",   case_cbz_holding_a_pdf, True),
    ("comicinfo_malformed",    case_comicinfo_malformed,     True),
    ("comicinfo_oversized",    case_comicinfo_oversized,     True),
    ("comicinfo_corrupt",      case_comicinfo_corrupt_entry, True),
    ("comicinfo_two_sidecars", case_comicinfo_two_sidecars,  True),
    ("solid_7z",      case_solid_7z,       False),
    ("rar5",          case_rar5,           False),
    ("avif",          case_avif,           False),
]

EXPECTED = Path(__file__).with_name("corpus-expected-sha256.json")

# Produced by the reproducible cases above; pinned in corpus-expected-sha256.json.
REPRODUCIBLE_FILES = [
    "01_basic_ltr.cbz", "02_no_comicinfo.cbz", "03_upper.CBZ", "04_mixed.CbZ",
    "05_upper.CB7", "06_zip_named_cbr.cbr", "07_deep_nesting.cbz", "08_nonascii_rtl.cbz",
    "09_two_pages.cbz", "10_junk_entries.cbz", "11_truncated.cbz", "12_giant_page.cbz",
    "18_comicinfo_behind_junk.cbz", "13_outline.pdf",
    "19_fixed_layout.epub", "20_epub_named_cbz.cbz", "21_epub_rtl.epub",
    "22_tar.cbt", "23_cbz_with_pdf_inside.cbz",
    "24_comicinfo_malformed.cbz", "25_comicinfo_oversized.cbz",
    "26_comicinfo_corrupt_entry.cbz", "27_comicinfo_two_sidecars.cbz",
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fp:
        for block in iter(lambda: fp.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def generate(out: Path, include_huge: bool) -> dict[str, str]:
    out.mkdir(parents=True, exist_ok=True)
    for name, fn, _ in CASES:
        print("  build %s" % name, flush=True)
        fn(out)
    if include_huge:
        print("  build huge (this writes ~2.1 GiB)", flush=True)
        case_huge(out)
    else:
        skip("17_huge_2gb.cbz", "opt-in; pass --include-huge (writes ~2.1 GiB)")

    digests = {p.name: sha256(p) for p in sorted(out.iterdir()) if p.is_file()}
    (out / "corpus-manifest.json").write_text(
        json.dumps({"seed": "0x%016X" % SEED,
                    "skipped": [{"case": c, "reason": w} for c, w in SKIPS],
                    "sha256": digests},
                   indent=2, sort_keys=True) + "\n", "utf-8")
    return digests


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="build/corpus", type=Path,
                    help="output directory (default: build/corpus)")
    ap.add_argument("--include-huge", action="store_true",
                    help="also emit the ~2.1 GiB CBZ (needs ~2.3 GiB free)")
    ap.add_argument("--check", action="store_true",
                    help="verify the reproducible cases against corpus-expected-sha256.json")
    ap.add_argument("--update-expected", action="store_true",
                    help="rewrite corpus-expected-sha256.json from this run")
    args = ap.parse_args(argv)

    out: Path = args.out
    print("Absolutex hostile corpus -> %s" % out.resolve())
    digests = generate(out, args.include_huge)

    if args.update_expected:
        pinned = {k: digests[k] for k in REPRODUCIBLE_FILES if k in digests}
        EXPECTED.write_text(json.dumps(pinned, indent=2, sort_keys=True) + "\n", "utf-8")
        print("wrote %s (%d entries)" % (EXPECTED.name, len(pinned)))

    if args.check:
        if not EXPECTED.exists():
            print("FAIL: %s is missing; run --update-expected" % EXPECTED, file=sys.stderr)
            return 2
        expected = json.loads(EXPECTED.read_text("utf-8"))
        bad: list[str] = []
        for name, want in sorted(expected.items()):
            got = digests.get(name)
            if got is None:
                bad.append("%s: not generated" % name)
            elif got != want:
                bad.append("%s: expected %s, got %s" % (name, want[:16], got[:16]))
        if bad:
            print("\nFAIL: corpus is not reproducible:", file=sys.stderr)
            for line in bad:
                print("  " + line, file=sys.stderr)
            return 1
        print("\nOK: %d reproducible cases match their pinned digests" % len(expected))

    print("\n%d files, %d case(s) skipped" % (len(digests), len(SKIPS)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
