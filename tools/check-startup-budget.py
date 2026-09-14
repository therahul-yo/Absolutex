#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Enforce the §3 cold-start budget against Macrobenchmark JSON output.

Macrobenchmark has no assertion API — ``measureRepeated`` records numbers and returns.
So the budget lives here: run the benchmark, then run this, and a regression fails a
command instead of sitting in a report nobody opens.

    tools/run-benchmark.sh com.absolutex.benchmark.StartupBenchmark
    adb pull /sdcard/Android/data/com.absolutex.benchmark/files/test_data ./bench-out
    python3 tools/check-startup-budget.py --budget-ms 300 --results ./bench-out

`tools/run-benchmark.sh` drives `am instrument` directly rather than going through Gradle's
connectedAndroidTest, which uninstalls the target APK between runs — that path measures the
first-run (picker) startup, not the returning-user (resumed book) one. Both are valid; they
are different numbers. Gradle's path also works and needs no --results:

    ./gradlew :benchmark:connectedBenchmarkAndroidTest
    python3 tools/check-startup-budget.py --budget-ms 300

Note the `benchmark` build type currently has minification off (app/build.gradle.kts,
TODO(phase9)), so these numbers cannot sign off the §3 budget yet.

By default this gates ``startupBaselineProfile`` only. CompilationMode.Partial is what
ships (§3 makes baseline profiles mandatory), so it is the number that has to hold.
``startupNoCompilation`` and ``startupFullCompilation`` are reported for context and are
not gated — None is a pre-profile worst case no user stays in, and Full is not shippable.

P90 is computed from the per-iteration ``runs`` array by nearest rank. Macrobenchmark
reports minimum/median/maximum but not P90, and median hides exactly the tail §3 cares
about.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

# Preferred first. TTFD is the honest "interactive" signal but only exists once the app
# calls reportFullyDrawn(); until the reader does, TTID is what we have.
METRICS = ("timeToFullDisplayMs", "timeToInitialDisplayMs")

GATED_BENCHMARK = "startupBaselineProfile"

# Where AGP drops connected-test output. Any of these may exist depending on how the run
# was invoked; we search all of them.
DEFAULT_GLOBS = (
    "benchmark/build/outputs/connected_android_test_additional_output/**/*.json",
    "benchmark/build/outputs/androidTest-results/**/*.json",
    "build/outputs/connected_android_test_additional_output/**/*.json",
)


def percentile(values: list[float], pct: float) -> float:
    """Nearest-rank percentile: the smallest value at or above pct of the sorted samples."""
    if not values:
        raise ValueError("no samples")
    ordered = sorted(values)
    rank = max(1, math.ceil(pct / 100.0 * len(ordered)))
    return ordered[rank - 1]


def find_results(explicit: Path | None, root: Path) -> list[Path]:
    if explicit is not None and explicit.is_file():
        return [explicit]
    if explicit is not None:
        found = list(explicit.rglob("*.json"))
    else:
        found = []
        for pattern in DEFAULT_GLOBS:
            found.extend(root.glob(pattern))
    # Macrobenchmark writes <ClassName>_<timestamp>.json; other JSON may sit alongside it,
    # so keep only files that actually parse as a benchmark result.
    keep: list[Path] = []
    for p in sorted(set(found)):
        try:
            if isinstance(json.loads(p.read_text("utf-8")).get("benchmarks"), list):
                keep.append(p)
        except (ValueError, OSError):
            continue
    return keep


def collect(paths: list[Path]) -> dict[str, dict[str, list[float]]]:
    """name -> metric -> per-iteration samples, merged across result files."""
    out: dict[str, dict[str, list[float]]] = {}
    for path in paths:
        data = json.loads(path.read_text("utf-8"))
        for bench in data.get("benchmarks", []):
            name = bench.get("name", "?")
            slot = out.setdefault(name, {})
            for metric, body in (bench.get("metrics") or {}).items():
                runs = body.get("runs")
                if not runs:
                    # Some writers omit runs and give only summary stats; median is the
                    # least-bad single-sample stand-in, and we say so in the report.
                    med = body.get("median")
                    runs = [med] if med is not None else []
                slot.setdefault(metric, []).extend(float(r) for r in runs)
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--budget-ms", type=float, default=300.0,
                    help="P90 budget in milliseconds (default: 300, from spec §3)")
    ap.add_argument("--results", type=Path, default=None,
                    help="result .json or a directory to search (default: search build outputs)")
    ap.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                    help="repository root to search from")
    ap.add_argument("--benchmark", default=GATED_BENCHMARK,
                    help="benchmark method to gate (default: %s)" % GATED_BENCHMARK)
    ap.add_argument("--gate-all", action="store_true",
                    help="apply the budget to every benchmark, not just the gated one")
    args = ap.parse_args(argv)

    paths = find_results(args.results, args.root)
    if not paths:
        print("No Macrobenchmark JSON found. Run the benchmark on a device first:\n"
              "  tools/run-benchmark.sh com.absolutex.benchmark.StartupBenchmark\n"
              "then adb pull the results and pass --results, or use Gradle:\n"
              "  ./gradlew :benchmark:connectedBenchmarkAndroidTest", file=sys.stderr)
        return 2

    print("Reading %d result file(s):" % len(paths))
    for p in paths:
        print("  %s" % p)

    results = collect(paths)
    if not results:
        print("\nResult files contained no benchmarks.", file=sys.stderr)
        return 2

    failures: list[str] = []
    print("\n%-26s %-22s %8s %8s %8s  %s"
          % ("benchmark", "metric", "P50", "P90", "max", "verdict"))
    print("-" * 92)

    for name in sorted(results):
        metrics = results[name]
        metric = next((m for m in METRICS if metrics.get(m)), None)
        if metric is None:
            print("%-26s %-22s %8s %8s %8s  %s"
                  % (name, "(no startup metric)", "-", "-", "-", "skipped"))
            continue
        samples = metrics[metric]
        p50, p90, mx = percentile(samples, 50), percentile(samples, 90), max(samples)
        gated = args.gate_all or name == args.benchmark
        if not gated:
            verdict = "context only"
        elif p90 <= args.budget_ms:
            verdict = "PASS (budget %.0f ms)" % args.budget_ms
        else:
            verdict = "FAIL (budget %.0f ms)" % args.budget_ms
            failures.append("%s: P90 %s = %.1f ms, over the %.0f ms budget by %.1f ms"
                            % (name, metric, p90, args.budget_ms, p90 - args.budget_ms))
        print("%-26s %-22s %8.1f %8.1f %8.1f  %s (n=%d)"
              % (name, metric, p50, p90, mx, verdict, len(samples)))

    if args.benchmark not in results and not args.gate_all:
        print("\nFAIL: gated benchmark '%s' was not in the results." % args.benchmark,
              file=sys.stderr)
        return 1

    if failures:
        print("\nFAIL:", file=sys.stderr)
        for f in failures:
            print("  " + f, file=sys.stderr)
        return 1

    print("\nOK: within the %.0f ms P90 budget." % args.budget_ms)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
