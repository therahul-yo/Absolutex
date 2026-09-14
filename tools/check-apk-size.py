#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Report APK size by category and fail on a regression against a committed baseline.

    python3 tools/check-apk-size.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
    python3 tools/check-apk-size.py --apk <path> --update      # re-arm the baseline

The baseline lives in ``.github/apk-size-baseline.json`` and is reviewed like any other
diff: a deliberate size increase shows up in the pull request as a changed number, with
whoever raised it on the hook to say why.

Until someone runs ``--update`` on a real build the baseline holds ``null``, the gate is
disarmed, and this reports without failing. It says so loudly rather than passing quietly —
a gate that has never been armed is not a gate.

Sizes are the **compressed, on-disk** bytes, because that is what the APK actually costs.
The uncompressed column is there to show where R8 and resource shrinking are working.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import zipfile
from pathlib import Path

# Ordered: first match wins, so lib/ is classified before the generic catch-all.
CATEGORIES: list[tuple[str, tuple[str, ...]]] = [
    ("dex",       (".dex",)),
    ("native",    ("lib/",)),
    ("resources", ("res/", "resources.arsc")),
    ("assets",    ("assets/",)),
    ("signing",   ("META-INF/",)),
    ("manifest",  ("AndroidManifest.xml",)),
]


def classify(name: str) -> str:
    for label, patterns in CATEGORIES:
        for pat in patterns:
            if (name.startswith(pat) if pat.endswith("/") or "/" in pat else
                    (name.endswith(pat) or name == pat)):
                return label
    return "other"


def measure(apk: Path) -> dict:
    per: dict[str, dict[str, int]] = {}
    with zipfile.ZipFile(apk) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            slot = per.setdefault(classify(info.filename), {"compressed": 0, "uncompressed": 0,
                                                            "entries": 0})
            slot["compressed"] += info.compress_size
            slot["uncompressed"] += info.file_size
            slot["entries"] += 1
    return {"total_bytes": apk.stat().st_size, "categories": per}


def human(n: int | float) -> str:
    step = 1024.0
    value = float(n)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(value) < step or unit == "GiB":
            return "%.1f %s" % (value, unit) if unit != "B" else "%d B" % value
        value /= step
    return "%.1f GiB" % value


def render(report: dict, baseline: dict | None, limit: int | None) -> str:
    lines = ["| Category | Compressed | Uncompressed | Entries |",
             "|---|---:|---:|---:|"]
    cats = report["categories"]
    for label in sorted(cats, key=lambda k: -cats[k]["compressed"]):
        c = cats[label]
        lines.append("| `%s` | %s | %s | %d |"
                     % (label, human(c["compressed"]), human(c["uncompressed"]), c["entries"]))
    total = report["total_bytes"]
    lines.append("| **APK total** | **%s** | | |" % human(total))
    lines.append("| | `%d bytes` | | |" % total)   # exact, so a CI log alone can arm the gate
    lines.append("")

    base_bytes = (baseline or {}).get("total_bytes")
    if base_bytes is None:
        lines.append("> **Size gate is not armed.** `.github/apk-size-baseline.json` has no "
                     "baseline yet. Run `python3 tools/check-apk-size.py --apk <path> --update` "
                     "on a build you trust and commit the result — or, to arm it from this run, "
                     "set `total_bytes` to `%d`." % total)
    else:
        delta = total - base_bytes
        pct = (delta / base_bytes * 100.0) if base_bytes else 0.0
        lines.append("Baseline **%s** → now **%s** (%s%s, %+.2f%%). Ceiling **%s**."
                     % (human(base_bytes), human(total),
                        "+" if delta >= 0 else "", human(delta), pct,
                        human(limit) if limit is not None else "n/a"))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", type=Path, required=True, help="APK to measure (or a directory)")
    ap.add_argument("--baseline", type=Path, default=Path(".github/apk-size-baseline.json"))
    ap.add_argument("--tolerance-pct", type=float, default=2.0,
                    help="allowed growth over baseline, percent (default: 2)")
    ap.add_argument("--tolerance-bytes", type=int, default=64 * 1024,
                    help="allowed growth over baseline, absolute; whichever is larger wins "
                         "(default: 64 KiB, so trivial churn does not trip the gate)")
    ap.add_argument("--update", action="store_true", help="rewrite the baseline from this APK")
    ap.add_argument("--summary", type=Path, default=None,
                    help="append a markdown report here (e.g. $GITHUB_STEP_SUMMARY)")
    args = ap.parse_args(argv)

    apk = args.apk
    if apk.is_dir():
        found = sorted(apk.glob("*.apk"))
        if not found:
            print("No .apk under %s" % apk, file=sys.stderr)
            return 2
        if len(found) > 1:
            print("WARNING: %d APKs under %s; measuring %s. Pass --apk explicitly."
                  % (len(found), apk, found[0].name), file=sys.stderr)
        apk = found[0]
    if not apk.is_file():
        print("No such APK: %s" % apk, file=sys.stderr)
        return 2

    report = measure(apk)
    baseline = None
    if args.baseline.is_file():
        baseline = json.loads(args.baseline.read_text("utf-8"))

    base_bytes = (baseline or {}).get("total_bytes")
    limit = None
    if base_bytes is not None:
        limit = base_bytes + max(int(base_bytes * args.tolerance_pct / 100.0),
                                 args.tolerance_bytes)

    text = render(report, baseline, limit)
    print("APK: %s" % apk)
    print(text)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as fh:
            fh.write("### APK size\n\n`%s`\n\n%s\n\n" % (apk.name, text))

    if args.update:
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        args.baseline.write_text(json.dumps(
            {"_comment": "Compressed on-disk size of the release APK. Regenerate with: "
                         "python3 tools/check-apk-size.py --apk <path> --update",
             "apk": apk.name,
             "total_bytes": report["total_bytes"],
             "categories": report["categories"]},
            indent=2, sort_keys=True) + "\n", "utf-8")
        print("\nBaseline written to %s (%s)" % (args.baseline, human(report["total_bytes"])))
        return 0

    if base_bytes is None:
        print("\nSize gate is NOT armed: %s has no baseline. Reporting only." % args.baseline,
              file=sys.stderr)
        return 0

    if report["total_bytes"] > limit:
        over = report["total_bytes"] - limit
        print("\nFAIL: APK is %s over the ceiling (%s vs %s allowed)."
              % (human(over), human(report["total_bytes"]), human(limit)), file=sys.stderr)
        print("If the growth is intended, re-arm with --update and explain it in the PR.",
              file=sys.stderr)
        return 1

    print("\nOK: %s, within the %s ceiling." % (human(report["total_bytes"]), human(limit)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
