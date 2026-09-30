#!/usr/bin/env python3
"""Generates the solid-7z test and benchmark archives with 7-Zip (`7zz`), into a scratch directory.

    make-solid-corpus.py OUT [--pages N] [--page-kib K]

Writes OUT/pages/ (N pseudo-random "PNG" pages of K KiB named 000.png.., plus a ComicInfo.xml that 7-Zip places
LAST in a solid block), then OUT/solid.7z (`-ms=on`, LZMA2), OUT/nonsolid.7z (`-ms=off`),
OUT/plain.zip and OUT/corrupt.7z (the solid archive with 4 KiB of garbage over its middle). The pages are seeded
pseudo-random bytes behind a PNG signature, drawn from a 200-symbol alphabet (about 7.6 bits of
entropy per byte): they barely compress, like a scan, but LZMA2 still codes them rather than
storing them raw, so decoding a page costs real time. Fully random bytes would be stored raw and
decode at memcpy speed, which understates the problem by two orders of magnitude. Nothing here is committed and nothing depends on it in CI; tools/test-archive-solid.sh
and tools/bench-solid.sh call it and delete the result.
"""
import argparse
import os
import random
import shutil
import subprocess
import sys

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ALPHABET = bytes(b % 200 for b in range(256))   # maps uniform bytes onto 200 symbols, slightly skewed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--pages", type=int, default=40)
    parser.add_argument("--page-kib", type=int, default=200)
    args = parser.parse_args()
    sevenzip = shutil.which("7zz") or shutil.which("7z")
    if sevenzip is None:
        print("7zz not found (brew install sevenzip)", file=sys.stderr)
        return 1
    pages = os.path.join(args.out, "pages")
    shutil.rmtree(pages, ignore_errors=True)
    os.makedirs(pages)
    rng = random.Random(7)
    for i in range(args.pages):
        body = rng.randbytes(args.page_kib * 1024 - len(PNG_SIGNATURE)).translate(ALPHABET)
        with open(os.path.join(pages, f"{i:03d}.png"), "wb") as f:
            f.write(PNG_SIGNATURE + body)
    with open(os.path.join(pages, "ComicInfo.xml"), "w") as f:
        f.write(f"<ComicInfo><Title>Solid</Title><PageCount>{args.pages}</PageCount></ComicInfo>")

    def build(name: str, *flags: str) -> str:
        path = os.path.join(args.out, name)
        if os.path.exists(path):
            os.remove(path)
        subprocess.run([sevenzip, "a", *flags, path, "."], cwd=pages, check=True,
                       stdout=subprocess.DEVNULL)
        return path

    solid = build("solid.7z", "-t7z", "-m0=lzma2", "-ms=on")
    build("nonsolid.7z", "-t7z", "-m0=lzma2", "-ms=off")
    build("plain.zip", "-tzip")
    with open(solid, "rb") as f:
        data = f.read()
    # The header sits at the END of a 7z, so a truncated file lists nothing; damage the middle
    # instead, which leaves the listing intact and breaks the block part-way through.
    middle = len(data) * 6 // 10
    with open(os.path.join(args.out, "corrupt.7z"), "wb") as f:
        f.write(data[:middle] + bytes(range(256)) * 16 + data[middle + 4096:])
    return 0


if __name__ == "__main__":
    sys.exit(main())
