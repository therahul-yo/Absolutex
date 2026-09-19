#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Reject *new* hardcoded user-visible strings in the UI modules (i18n milestone 1).

    python3 tools/check-strings.py                 # gate: fail on anything not baselined
    python3 tools/check-strings.py --check         # self-test the detector, touches no sources
    python3 tools/check-strings.py --update-baseline
    python3 tools/check-strings.py --list          # print every finding, baselined or not

Why this exists
---------------
A string that never reaches ``strings.xml`` cannot be translated, and it is invisible in
review: ``Text("Retry")`` looks exactly like ``Text(stringResource(R.string.retry))`` to a
reader skimming a diff. The pseudo-locale render test catches these too, but it needs an
emulator and the Android SDK. This runs in seconds on any machine with Python, which is why
it lives in the hygiene job and not in the Gradle build.

Scope: ``src/main`` of the modules that draw UI -- ``feature/*``, ``core/ui``, ``core/gpu``.
The rest of the tree is SQL in ``@Query``, log tags, ``@Suppress`` names and exception
messages; scanning it produces noise and no signal. An over-broad sweep of all 20 modules
returned 276 candidates, of which 269 were one of those four things.

Why a baseline and not a clean gate
-----------------------------------
The milestone is "reject *new* literals". The tree already holds a handful -- some are real
defects queued for milestone 2, and some are deliberate (a glyph button that carries its
label in ``semantics { contentDescription }``). Failing on all of them on day one would
either block the CI job or force a same-day edit of modules other lanes are mid-change in.
So known findings are pinned in ``tools/strings-baseline.json``, reviewed like any other
diff, and anything *not* in it fails.

The baseline is keyed by **module path, kind and the string itself** -- never by line
number. A line-numbered baseline goes stale on every unrelated edit above it, and a gate
that cries wolf gets disabled within a week.

Suppression
-----------
A literal that is genuinely not translatable text is marked in the source, on the line or
the line above:

    // i18n-ignore: decorative glyph; the label is on the parent's semantics block
    ) { Text("✕") }

The reason is **required**. A bare ``// i18n-ignore`` is itself a finding: the next person
reading the diff has to be able to see why, without knowing that a magic list of exempt
code points exists somewhere.
"""

from __future__ import annotations

import argparse
import bisect
import json
import re
from collections import Counter
from pathlib import Path

# Modules that draw UI. Everything else is machine strings -- see the module docstring.
UI_MODULES: tuple[str, ...] = ("feature/*", "core/ui", "core/gpu")

BASELINE = Path("tools/strings-baseline.json")

# `// i18n-ignore: <reason>` -- the reason is a required capture, not an optional tail.
IGNORE_WITH_REASON = re.compile(r"//\s*i18n-ignore\s*:\s*(\S.*?)\s*$")
IGNORE_BARE = re.compile(r"//\s*i18n-ignore\s*:?\s*$")

# Call sites that put a string in front of a human. Deliberately NOT here: a bare
# `label = "..."`. Compose uses that name for tooling labels (`animateColorAsState(label =)`)
# far more often than for UI text, and the UI cases pass a composable that contains a
# Text(...) we already scan. Including it reported `label = "readerBackground"` as a
# user-visible string, which it is not.
TEXT_CALL = re.compile(r"\bText\s*\(")
DESCRIPTION_ARG = re.compile(r"\b(contentDescription|stateDescription)\s*=\s*\"")
TOAST_CALL = re.compile(r"\bToast\s*\.\s*makeText\s*\(")
SNACKBAR_CALL = re.compile(r"\bshowSnackbar\s*\(")
NOTIFICATION_SETTER = re.compile(r"\.\s*set(?:ContentTitle|ContentText|Ticker|SubText)\s*\(\s*\"")

KIND_TEXT = "text"
KIND_TEXT_INTERPOLATED = "text-interpolated"
KIND_DESCRIPTION = "content-description"
KIND_TOAST = "toast"
KIND_SNACKBAR = "snackbar"
KIND_NOTIFICATION = "notification"
KIND_IGNORE_NO_REASON = "ignore-without-reason"


class Finding:
    """One user-visible literal, or one malformed suppression."""

    __slots__ = ("path", "line", "kind", "text")

    def __init__(self, path: str, line: int, kind: str, text: str) -> None:
        self.path, self.line, self.kind, self.text = path, line, kind, text

    def key(self) -> str:
        """Identity for the baseline: position-free, so unrelated edits never disturb it."""
        return f"{self.path}\t{self.kind}\t{self.text}"

    def __repr__(self) -> str:
        return f"{self.path}:{self.line}  [{self.kind}]  {self.text!r}"


# --------------------------------------------------------------------------------------
# Kotlin lexing
#
# Regex alone cannot do this: `Text("http://example.com")` contains `//`, and a literal
# inside a comment is not a finding. So one pass blanks comments -- preserving every byte
# offset and every newline, so line numbers stay exact -- and records the suppressions.
# --------------------------------------------------------------------------------------


def _skip_string(src: str, i: int) -> int:
    """Return the offset just past the string literal starting at ``i``."""
    n = len(src)
    if src.startswith('"""', i):
        end = src.find('"""', i + 3)
        return n if end < 0 else end + 3
    i += 1
    while i < n:
        c = src[i]
        if c == "\\":
            i += 2
            continue
        if c == '"':
            return i + 1
        if c == "\n":          # unterminated; stop at the line end rather than run away
            return i
        i += 1
    return n


def _skip_char(src: str, i: int) -> int:
    n = len(src)
    i += 1
    while i < n:
        if src[i] == "\\":
            i += 2
            continue
        if src[i] == "'":
            return i + 1
        i += 1
    return n


def lex(src: str) -> tuple[str, dict[int, str | None], list[int]]:
    """Blank comments, collect `i18n-ignore` suppressions, and index line starts.

    Returns ``(blanked, ignores, line_starts)`` where ``ignores`` maps a line number to its
    reason (``None`` when the marker carried no reason).
    """
    out = list(src)
    ignores: dict[int, str | None] = {}
    line_starts = [0] + [m.end() for m in re.finditer(r"\n", src)]
    i, n = 0, len(src)

    def line_of(offset: int) -> int:
        return bisect.bisect_right(line_starts, offset)

    while i < n:
        c = src[i]
        if c == '"':
            i = _skip_string(src, i)
        elif c == "'":
            i = _skip_char(src, i)
        elif src.startswith("//", i):
            end = src.find("\n", i)
            end = n if end < 0 else end
            body = src[i:end]
            with_reason = IGNORE_WITH_REASON.search(body)
            if with_reason:
                ignores[line_of(i)] = with_reason.group(1)
            elif IGNORE_BARE.search(body):
                ignores[line_of(i)] = None
            for k in range(i, end):
                out[k] = " "
            i = end
        elif src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:                      # Kotlin block comments nest
                if src.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif src.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            for k in range(i, min(j, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out), ignores, line_starts


def split_args(src: str, i: int) -> list[tuple[str, int]]:
    """Split a call's argument list, starting just after its `(`.

    Depth-aware and string-aware, so `Text(if (b) a(x, y) else "z")` yields one argument,
    not three.
    """
    args: list[tuple[str, int]] = []
    depth, start, cur, n = 0, i, [], len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = _skip_string(src, i)
            cur.append(src[i:j])
            i = j
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                args.append(("".join(cur), start))
                return args
            depth -= 1
        elif c == "," and depth == 0:
            args.append(("".join(cur), start))
            i += 1
            start, cur = i, []
            continue
        cur.append(c)
        i += 1
    args.append(("".join(cur), start))
    return args


LITERAL_ARG = re.compile(r'^\s*(?:text\s*=\s*)?"((?:[^"\\]|\\.)*)"\s*$', re.DOTALL)
NAMED_ARG = re.compile(r"^\s*(\w+)\s*=")


def _literal_of(arg: str) -> str | None:
    """The string literal an argument consists of, or None if it is an expression."""
    m = LITERAL_ARG.match(arg)
    return m.group(1) if m else None


def _text_argument(args: list[tuple[str, int]]) -> tuple[str, int] | None:
    """The `text` argument of a Text(...) call: the named one, else the first positional."""
    for arg, offset in args:
        named = NAMED_ARG.match(arg)
        if named and named.group(1) == "text":
            return arg, offset
    if args:
        arg, offset = args[0]
        named = NAMED_ARG.match(arg)
        if not named:                     # a leading `style = ...` means no positional text
            return arg, offset
    return None


# --------------------------------------------------------------------------------------
# Detection
# --------------------------------------------------------------------------------------


def _suppressed(line: int, ignores: dict[int, str | None]) -> bool:
    """A marker on the line itself or the line above suppresses, but only with a reason."""
    return ignores.get(line) is not None or ignores.get(line - 1) is not None


def _record(found: list[Finding], path: str, line: int, kind: str, literal: str) -> None:
    if not literal.strip():
        return                                  # `Text("")` is a spacer, not a message
    found.append(Finding(path, line, kind, literal))


def _scan_call_first_literal(
    blanked: str, pattern: re.Pattern[str], kind: str, path: str,
    line_of, found: list[Finding],
) -> None:
    """Flag calls whose first argument is a bare literal (Toast, Snackbar)."""
    for m in pattern.finditer(blanked):
        args = split_args(blanked, m.end())
        # Toast.makeText takes the context first; the message is the second argument.
        index = 1 if kind == KIND_TOAST else 0
        if len(args) <= index:
            continue
        literal = _literal_of(args[index][0])
        if literal is not None:
            _record(found, path, line_of(args[index][1]), kind, literal)


def find_in_source(path: str, src: str) -> list[Finding]:
    """Every user-visible literal in one Kotlin file, suppressions already applied."""
    blanked, ignores, line_starts = lex(src)
    found: list[Finding] = []

    def line_of(offset: int) -> int:
        return bisect.bisect_right(line_starts, offset)

    for line, reason in ignores.items():
        if reason is None:
            found.append(Finding(path, line, KIND_IGNORE_NO_REASON,
                                 "i18n-ignore without a reason"))

    for m in TEXT_CALL.finditer(blanked):
        argument = _text_argument(split_args(blanked, m.end()))
        if argument is None:
            continue
        arg, offset = argument
        literal = _literal_of(arg)
        if literal is None:
            continue
        kind = KIND_TEXT_INTERPOLATED if "$" in literal else KIND_TEXT
        _record(found, path, line_of(offset), kind, literal)

    for m in DESCRIPTION_ARG.finditer(blanked):
        quote = blanked.index('"', m.start())
        literal = blanked[quote + 1:_skip_string(blanked, quote) - 1]
        _record(found, path, line_of(quote), KIND_DESCRIPTION, literal)

    for m in NOTIFICATION_SETTER.finditer(blanked):
        quote = blanked.rindex('"', m.start(), m.end())
        literal = blanked[quote + 1:_skip_string(blanked, quote) - 1]
        _record(found, path, line_of(quote), KIND_NOTIFICATION, literal)

    _scan_call_first_literal(blanked, TOAST_CALL, KIND_TOAST, path, line_of, found)
    _scan_call_first_literal(blanked, SNACKBAR_CALL, KIND_SNACKBAR, path, line_of, found)

    return [f for f in found
            if f.kind == KIND_IGNORE_NO_REASON or not _suppressed(f.line, ignores)]


def sources(root: Path) -> list[Path]:
    """Every `src/main` Kotlin file in the UI modules, sorted for a stable report."""
    out: list[Path] = []
    for pattern in UI_MODULES:
        for module in sorted(root.glob(pattern)):
            out.extend(sorted((module / "src" / "main").rglob("*.kt")))
    return out


def scan_tree(root: Path) -> list[Finding]:
    found: list[Finding] = []
    for path in sources(root):
        rel = path.relative_to(root).as_posix()
        found.extend(find_in_source(rel, path.read_text(encoding="utf-8")))
    return found


# --------------------------------------------------------------------------------------
# Self-test
#
# Fixtures, not repository sources: the gate has to keep working when the tree is clean,
# and a detector asserted only against today's code stops being asserted the moment
# someone fixes the code.
# --------------------------------------------------------------------------------------

FIXTURES: tuple[tuple[str, str, set[tuple[str, str]]], ...] = (
    ("plain literal", 'Text("Retry")', {(KIND_TEXT, "Retry")}),
    ("named argument", 'Text(text = "Retry")', {(KIND_TEXT, "Retry")}),
    ("multi-line named argument",
     'Text(\n    text = "Saved",\n    style = MaterialTheme.typography.labelSmall,\n)',
     {(KIND_TEXT, "Saved")}),
    ("resource lookup is clean", 'Text(stringResource(R.string.reader_retry))', set()),
    ("context.getString is clean (Glance)",
     'Text(text = context.getString(R.string.widget_title))', set()),
    ("conditional resource is clean",
     'Text(if (done) stringResource(R.string.a) else stringResource(R.string.b))', set()),
    ("interpolation is its own kind",
     'Text("${stringResource(R.string.library_layout)}: ${layout.label()}")',
     {(KIND_TEXT_INTERPOLATED, "${stringResource(R.string.library_layout)}: ${layout.label()}")}),
    ("empty string is a spacer", 'Text("")', set()),
    ("content description", 'Modifier.semantics { contentDescription = "Close" }',
     {(KIND_DESCRIPTION, "Close")}),
    ("null content description", "Icon(Icons.Filled.Add, contentDescription = null)", set()),
    ("state description", 'stateDescription = "Unread"', {(KIND_DESCRIPTION, "Unread")}),
    ("toast takes the second argument",
     'Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()',
     {(KIND_TOAST, "Saved")}),
    ("snackbar", 'scope.launch { snackbar.showSnackbar("Couldn\'t open") }',
     {(KIND_SNACKBAR, "Couldn't open")}),
    ("notification setter", '.setContentTitle("Scanning")',
     {(KIND_NOTIFICATION, "Scanning")}),
    # The lexer earns its keep on these four.
    ("literal in a line comment", '// Text("Retry") is what this used to be', set()),
    ("literal in a block comment", '/* Text("Retry") */', set()),
    ("literal in a nested block comment", '/* outer /* Text("Retry") */ still */', set()),
    ("slashes inside a literal are not a comment",
     'Text("http://example.com")', {(KIND_TEXT, "http://example.com")}),
    # False positives found by hand during the inventory; each one is a regression guard.
    ("tooling label is not UI text",
     'val c by animateColorAsState(target, label = "readerBackground")', set()),
    ("log call is not UI text", 'Log.w("Shell", "persistable grant refused", it)', set()),
    ("SQL is not UI text", '@Query("SELECT * FROM library_book ORDER BY series")', set()),
    ("suppress annotation is not UI text", '@Suppress("TooGenericExceptionCaught")', set()),
    # Suppression.
    ("marker on the line above suppresses",
     '// i18n-ignore: decorative glyph, labelled on the parent\nText("✕")', set()),
    ("marker on the same line suppresses",
     'Text("⋮") // i18n-ignore: decorative glyph, labelled on the parent', set()),
    ("marker without a reason is itself a finding",
     '// i18n-ignore\nText("✕")',
     {(KIND_IGNORE_NO_REASON, "i18n-ignore without a reason"), (KIND_TEXT, "✕")}),
    ("marker with a colon but no reason is a finding",
     '// i18n-ignore:\nText("✕")',
     {(KIND_IGNORE_NO_REASON, "i18n-ignore without a reason"), (KIND_TEXT, "✕")}),
    ("a reason does not suppress two lines down",
     '// i18n-ignore: applies to the next line only\nval x = 1\nText("Retry")',
     {(KIND_TEXT, "Retry")}),
)


def _baseline_cases() -> list[tuple[str, bool]]:
    """(name, passed) for the baseline arithmetic -- the part the fixtures cannot reach."""
    def finding(text: str, line: int = 1) -> Finding:
        return Finding("a.kt", line, KIND_TEXT, text)

    two_glyphs = [finding("\u2715", 1), finding("\u2715", 2)]
    allowed_two = {finding("\u2715").key(): 2}
    allowed_one = {finding("\u2715").key(): 1}
    return [
        ("baseline: an exactly-matched count is clean",
         excess_over_baseline(two_glyphs, allowed_two) == []),
        ("baseline: a third occurrence is new",
         len(excess_over_baseline(two_glyphs + [finding("\u2715", 3)], allowed_two)) == 1),
        ("baseline: an under-budget tree is clean, not an error",
         excess_over_baseline([finding("\u2715", 1)], allowed_two) == []),
        ("baseline: an unbaselined string is new",
         len(excess_over_baseline([finding("Retry")], allowed_two)) == 1),
        ("baseline: a count of one allows exactly one",
         len(excess_over_baseline(two_glyphs, allowed_one)) == 1),
        ("baseline: an empty baseline reports everything",
         len(excess_over_baseline(two_glyphs, {})) == 2),
    ]


def self_test() -> int:
    failures = 0
    for name, source, expected in FIXTURES:
        actual = {(f.kind, f.text) for f in find_in_source("fixture.kt", source)}
        if actual != expected:
            failures += 1
            print(f"FAIL  {name}")
            for missing in sorted(expected - actual):
                print(f"        expected, not found: {missing}")
            for extra in sorted(actual - expected):
                print(f"        found, not expected: {extra}")
    baseline_cases = _baseline_cases()
    for name, passed in baseline_cases:
        if not passed:
            failures += 1
            print(f"FAIL  {name}")
    total = len(FIXTURES) + len(baseline_cases)
    if failures:
        print(f"\n{failures} of {total} cases failed")
        return 1
    print(f"OK: {total} cases pass ({len(FIXTURES)} detector, {len(baseline_cases)} baseline)")
    return 0


# --------------------------------------------------------------------------------------
# Baseline and reporting
# --------------------------------------------------------------------------------------


def load_baseline(path: Path) -> dict[str, int]:
    """Map each known finding to how many times it is allowed to occur.

    The count matters: `LibraryBars.kt` holds two identical `Text("\u2715")` calls. Without
    it, a set would collapse them to one entry and a third copy would pass the gate
    unnoticed -- which is precisely the kind of quiet spread this tool exists to stop.
    """
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {f"{e['path']}\t{e['kind']}\t{e['text']}": int(e.get("count", 1))
            for e in data.get("known", [])}


def write_baseline(path: Path, found: list[Finding]) -> None:
    counts = Counter(f.key() for f in found)
    entries = []
    for key, count in counts.items():
        path_, kind, text = key.split("\t", 2)
        entries.append({"path": path_, "kind": kind, "text": text, "count": count})
    entries.sort(key=lambda e: (e["path"], e["kind"], e["text"]))
    payload = {
        "_comment": (
            "Known hardcoded user-visible strings, pinned so the gate fails only on NEW "
            "ones. Keyed by path+kind+text, never by line number. Shrinking this file is "
            "the point: every entry is either a milestone-2 externalisation or wants a "
            "'// i18n-ignore: <reason>' comment in the source. Regenerate with "
            "tools/check-strings.py --update-baseline."
        ),
        "known": entries,
    }
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def excess_over_baseline(found: list[Finding], known: dict[str, int]) -> list[Finding]:
    """Findings beyond what the baseline permits, in source order.

    Occurrences past the allowed count are reported; the baselined ones are not. Which
    specific occurrence gets reported is arbitrary -- they are identical by definition --
    so the earliest are treated as the known ones and the surplus is what shows up.
    """
    budget = dict(known)
    new: list[Finding] = []
    for finding in sorted(found, key=lambda f: (f.path, f.line)):
        key = finding.key()
        if budget.get(key, 0) > 0:
            budget[key] -= 1
        else:
            new.append(finding)
    return new


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=Path("."), help="repository root")
    ap.add_argument("--baseline", type=Path, default=None,
                    help=f"baseline file (default: {BASELINE})")
    ap.add_argument("--check", action="store_true",
                    help="run the detector self-test and exit; reads no repository source")
    ap.add_argument("--update-baseline", action="store_true",
                    help="rewrite the baseline from the current tree")
    ap.add_argument("--list", action="store_true",
                    help="print every finding, baselined or not")
    ap.add_argument("--summary", type=Path, default=None,
                    help="append a Markdown report here (GITHUB_STEP_SUMMARY)")
    args = ap.parse_args(argv)

    if args.check:
        return self_test()

    baseline_path = args.baseline or (args.root / BASELINE)
    found = scan_tree(args.root)

    if args.update_baseline:
        write_baseline(baseline_path, found)
        print(f"baseline rewritten with {len(found)} finding(s): {baseline_path}")
        return 0

    known = load_baseline(baseline_path)
    new = excess_over_baseline(found, known)
    live = Counter(f.key() for f in found)
    stale = sorted(key for key, count in known.items() if live[key] < count)

    scanned = len(sources(args.root))
    if args.list:
        surplus = set(map(id, new))
        for f in sorted(found, key=lambda f: (f.path, f.line)):
            mark = "NEW  " if id(f) in surplus else "known"
            print(f"  {mark}  {f!r}")

    for f in sorted(new, key=lambda f: (f.path, f.line)):
        print(f"{f.path}:{f.line}: [{f.kind}] hardcoded user-visible string: {f.text!r}")

    if args.summary:
        lines = [f"### Hardcoded strings\n",
                 f"{scanned} UI source file(s) scanned · {len(found)} finding(s) · "
                 f"{sum(known.values())} baselined · **{len(new)} new**\n"]
        lines += [f"- `{f.path}:{f.line}` [{f.kind}] `{f.text}`" for f in new]
        if stale:
            lines.append(f"\n{len(stale)} baseline entry/entries no longer present — "
                         f"run `--update-baseline` to shrink it.")
        with args.summary.open("a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")

    if stale:
        print(f"note: {len(stale)} baseline entry/entries no longer in the tree "
              f"(a string was fixed or moved). Run --update-baseline to shrink it.")
        for key in stale:
            path, kind, text = key.split("\t", 2)
            print(f"      {path}  [{kind}]  {text!r}")

    if new:
        plural = "s" if len(new) != 1 else ""
        print(f"\n{len(new)} new hardcoded user-visible string{plural} in "
              f"{', '.join(UI_MODULES)}.")
        print("Move it to res/values/strings.xml and read it with stringResource(), or, if "
              "it is genuinely not translatable text, mark it in the source with")
        print("    // i18n-ignore: <why this is not user-visible text>")
        return 1

    if not scanned:
        print(f"error: no UI sources found under {args.root} "
              f"({', '.join(UI_MODULES)}). A gate that scans nothing is not a gate.")
        return 2

    print(f"OK: {scanned} UI source file(s), no new hardcoded strings "
          f"({sum(known.values())} baselined)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
