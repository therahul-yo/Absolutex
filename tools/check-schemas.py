#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Every Room ``@Database`` version must have its exported schema committed to git.

    python3 tools/check-schemas.py            # gate: fail if an exported schema is missing
    python3 tools/check-schemas.py --check    # self-test, touches no repository source
    python3 tools/check-schemas.py --list     # print what was found, pass or fail

Why this exists
---------------
Room writes ``schemas/<database fqn>/<version>.json`` at compile time, and
``DatabaseSchemaTest`` reads those files back. Both halves run inside the same build, so the
test finds a schema the compiler wrote seconds earlier **whether or not the file is tracked
in git**. Version 6 was merged with ``6.json`` absent from the branch and all four checks
green; the guard that "6.json was exported at all" passed because the build had just
exported it.

The point is not tidiness. An exported schema that is regenerated on every build is not a
record of anything: edit the entity later and ``6.json`` is silently rewritten in place, so
the 5 -> 6 migration is validated against whatever the entity says *now* rather than against
what shipped to a device. The file has to be committed for the migration to have a fixed
point to be checked against -- and once it is committed, rewriting it shows up as a diff on
a tracked file, which a reviewer can see.

No test inside the build can enforce this, because the build is what creates the file. That
is why it is a gate in the hygiene job rather than a test in ``:core:data``.

What it checks
--------------
A. a schema file exists for every version 1..N of a ``@Database(version = N)``
B. every one of those files is **tracked in git** -- the one CI cannot otherwise see
C. the file for version N declares ``"version": N`` inside it, so a bump that never
   re-exported is caught rather than silently validated against the previous schema
D. no schema file above N is left behind, which is what a reverted bump looks like

Deliberately not checked: that the exported entity list matches the ``entities = [...]`` in
the annotation. After a rewrite the two agree, so the comparison cannot see the failure this
gate exists for, and it would fail loudly on a legitimate rename. B is what catches it.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

# `version = 6`, with whatever spacing. Room requires an integer literal here, so there is
# no expression to evaluate -- if that ever stops being true the parse returns nothing and
# the gate says so rather than guessing.
VERSION_RE = re.compile(r"\bversion\s*=\s*(\d+)")
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
# The class the annotation is attached to: `abstract class AbsolutexDatabase : RoomDatabase()`
DB_CLASS_RE = re.compile(r"\babstract\s+class\s+(\w+)\s*:\s*RoomDatabase")
SCHEMA_FILE_RE = re.compile(r"^(\d+)\.json$")


@dataclass
class Database:
    """One `@Database` declaration and the schema directory it exports to."""

    source: Path          # the .kt file, repo-relative
    fqn: str              # com.absolutex.core.data.AbsolutexDatabase
    version: int
    schema_dir: Path      # <module>/schemas/<fqn>, repo-relative


@dataclass
class Report:
    databases: list[Database] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)


def module_root(source: Path) -> Path | None:
    """The Gradle module a source file belongs to: the path above its `src/` directory.

    Room's `room.schemaLocation` is set to `$projectDir/schemas` in every module here, so
    the module root is what locates the export. A file outside any `src/` is not in a
    module and is skipped rather than guessed at.
    """
    parts = source.parts
    if "src" not in parts:
        return None
    return Path(*parts[: parts.index("src")])


def parse_database(text: str, source: Path) -> tuple[Database | None, str | None]:
    """Pull one `@Database` out of a source file. Returns (database, problem)."""
    at = text.find("@Database")
    if at < 0:
        return None, None

    # The annotation's argument list, balanced-paren scanned rather than regex-matched:
    # `entities = [...]` contains brackets and commas, and a non-greedy `\(.*?\)` stops at
    # the first `)` inside `RoomDatabase()`.
    open_paren = text.find("(", at)
    if open_paren < 0:
        return None, f"{source}: @Database has no argument list"
    depth, close = 0, -1
    for i in range(open_paren, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                close = i
                break
    if close < 0:
        return None, f"{source}: @Database argument list is unterminated"

    args = text[open_paren : close + 1]
    version_match = VERSION_RE.search(args)
    if not version_match:
        return None, f"{source}: @Database has no integer `version =`"
    version = int(version_match.group(1))

    package_match = PACKAGE_RE.search(text)
    class_match = DB_CLASS_RE.search(text, close)
    if not package_match:
        return None, f"{source}: no package declaration"
    if not class_match:
        return None, f"{source}: no `abstract class ... : RoomDatabase` after @Database"

    root = module_root(source)
    if root is None:
        return None, f"{source}: not inside a module's src/ directory"

    fqn = f"{package_match.group(1)}.{class_match.group(1)}"
    return Database(source=source, fqn=fqn, version=version,
                    schema_dir=root / "schemas" / fqn), None


def find_databases(root: Path) -> tuple[list[Database], list[str]]:
    found: list[Database] = []
    problems: list[str] = []
    for path in sorted(root.rglob("*.kt")):
        if "/build/" in path.as_posix() or "/src/test/" in path.as_posix():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            problems.append(f"{path}: unreadable ({exc})")
            continue
        if "@Database" not in text:
            continue
        db, problem = parse_database(text, path.relative_to(root))
        if problem:
            problems.append(problem)
        elif db:
            found.append(db)
    return found, problems


def tracked_files(root: Path, directory: Path) -> set[str] | None:
    """Names git is tracking in `directory`. None when git cannot answer.

    `git ls-files` lists the index, which is exactly the question: a file the build just
    wrote is on disk and absent here until someone adds it.
    """
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--", directory.as_posix()],
            capture_output=True, text=True, timeout=30, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    return {Path(p).name for p in out.stdout.split("\0") if p}


def check_database(root: Path, db: Database) -> list[str]:
    problems: list[str] = []
    abs_dir = root / db.schema_dir

    if not abs_dir.is_dir():
        return [f"{db.fqn}: no exported schema directory at {db.schema_dir} "
                f"(expected versions 1..{db.version})"]

    on_disk = {int(m.group(1)): p for p in abs_dir.iterdir()
               if (m := SCHEMA_FILE_RE.match(p.name))}
    tracked = tracked_files(root, db.schema_dir)
    if tracked is None:
        return [f"{db.fqn}: git could not list {db.schema_dir}; cannot tell an exported "
                f"schema from a committed one, which is the whole check"]

    for version in range(1, db.version + 1):
        name = f"{version}.json"
        if version not in on_disk:
            problems.append(f"{db.fqn}: {db.schema_dir}/{name} is missing — run a build of "
                            f"the module to export it")
        elif name not in tracked:
            # The case this gate exists for: present because the build just wrote it,
            # absent from git, and every test that reads it still passes.
            problems.append(f"{db.fqn}: {db.schema_dir}/{name} exists but is NOT tracked by "
                            f"git — the build exported it and nobody committed it; "
                            f"`git add {db.schema_dir}/{name}`")

    newest = on_disk.get(db.version)
    if newest is not None:
        problems.extend(check_declared_version(newest, db))

    for stray in sorted(v for v in on_disk if v > db.version):
        problems.append(f"{db.fqn}: {db.schema_dir}/{stray}.json is above the declared "
                        f"version {db.version} — a reverted bump leaves this behind")

    return problems


def check_declared_version(path: Path, db: Database) -> list[str]:
    """Assertion C: the newest file says it is the version the annotation declares."""
    try:
        declared = json.loads(path.read_text(encoding="utf-8"))["database"]["version"]
    except (OSError, ValueError, KeyError) as exc:
        return [f"{db.fqn}: {path.name} is not a readable exported schema ({exc})"]
    if declared != db.version:
        return [f"{db.fqn}: {path.name} declares version {declared} but @Database says "
                f"{db.version} — the version was bumped without re-exporting, so the "
                f"migration into {db.version} is validated against the older schema"]
    return []


def run(root: Path) -> Report:
    databases, problems = find_databases(root)
    report = Report(databases=databases, problems=list(problems))
    for db in databases:
        report.problems.extend(check_database(root, db))
    return report


# ---------------------------------------------------------------------------
# Self-test. Builds real git repositories in a temp directory, because the one
# assertion that matters -- "tracked by git" -- cannot be faked with a fixture
# tree on disk. A test that stubbed `git ls-files` would pass with the bug.
# ---------------------------------------------------------------------------

DB_SOURCE = """package com.absolutex.core.data

import androidx.room.Database

@Database(
    entities = [
        ReadingProgress::class, LibraryBook::class, Bookmark::class,
    ],
    version = {version},
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AbsolutexDatabase : RoomDatabase() {{
    abstract fun progressDao(): ProgressDao
}}
"""

FQN = "com.absolutex.core.data.AbsolutexDatabase"
SCHEMA_REL = Path("core/data/schemas") / FQN


def _git(root: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(root), *args],
                   capture_output=True, check=True, timeout=30)


def build_fixture(root: Path, version: int, on_disk: list[int],
                  tracked: list[int] | None = None,
                  declared: dict[int, int] | None = None) -> None:
    """A repo with one @Database at `version` and schema files `on_disk`.

    `tracked` are the ones committed; the rest are written but left out of the index,
    which is exactly what a build that nobody committed leaves behind. `declared`
    overrides the version recorded *inside* a file.
    """
    src = root / "core/data/src/main/kotlin/com/absolutex/core/data"
    src.mkdir(parents=True)
    (src / "Progress.kt").write_text(DB_SOURCE.format(version=version), encoding="utf-8")

    schemas = root / SCHEMA_REL
    schemas.mkdir(parents=True)
    for v in on_disk:
        inner = (declared or {}).get(v, v)
        (schemas / f"{v}.json").write_text(
            json.dumps({"formatVersion": 1, "database": {"version": inner, "entities": []}}),
            encoding="utf-8")

    _git(root, "init", "-q")
    _git(root, "config", "user.email", "t@example.com")
    _git(root, "config", "user.name", "t")
    _git(root, "add", "core/data/src")
    for v in (on_disk if tracked is None else tracked):
        _git(root, "add", (SCHEMA_REL / f"{v}.json").as_posix())
    _git(root, "commit", "-qm", "fixture")


def _cases() -> list[tuple[str, dict, int, str]]:
    """(name, build_fixture kwargs, expected problem count, substring in the message)."""
    return [
        ("all versions exported and committed",
         dict(version=3, on_disk=[1, 2, 3]), 0, ""),
        ("one version never exported",
         dict(version=3, on_disk=[1, 2]), 1, "is missing"),
        ("exported but not committed — the case this gate exists for",
         dict(version=3, on_disk=[1, 2, 3], tracked=[1, 2]), 1, "NOT tracked by git"),
        ("none of the schemas committed",
         dict(version=2, on_disk=[1, 2], tracked=[]), 2, "NOT tracked by git"),
        ("bumped without re-exporting",
         dict(version=3, on_disk=[1, 2, 3], declared={3: 2}), 1, "without re-exporting"),
        ("a reverted bump left a file behind",
         dict(version=2, on_disk=[1, 2, 3]), 1, "above the declared version"),
        ("version 1 database, committed",
         dict(version=1, on_disk=[1]), 0, ""),
    ]


def self_test() -> int:
    passed = failed = 0

    def check(name: str, got, want, detail: str = "") -> None:
        nonlocal passed, failed
        if got == want:
            passed += 1
        else:
            failed += 1
            print(f"  FAIL {name}: got {got!r}, want {want!r} {detail}")

    for name, kwargs, want_count, substring in _cases():
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            build_fixture(root, **kwargs)
            report = run(root)
            check(f"[{name}] problem count", len(report.problems), want_count,
                  f"-> {report.problems}")
            if substring:
                hit = any(substring in p for p in report.problems)
                check(f"[{name}] message names the cause", hit, True,
                      f"-> {report.problems}")
            check(f"[{name}] found the database", len(report.databases), 1)

    # No schema directory at all is a problem, not a silent pass.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        src = root / "core/data/src/main/kotlin/com/absolutex/core/data"
        src.mkdir(parents=True)
        (src / "Progress.kt").write_text(DB_SOURCE.format(version=2), encoding="utf-8")
        _git(root, "init", "-q")
        _git(root, "config", "user.email", "t@example.com")
        _git(root, "config", "user.name", "t")
        _git(root, "add", ".")
        _git(root, "commit", "-qm", "fixture")
        report = run(root)
        check("[no schemas dir] one problem", len(report.problems), 1, f"-> {report.problems}")
        check("[no schemas dir] says so",
              any("no exported schema directory" in p for p in report.problems), True)

    # Outside a git repository the answer is "cannot tell", never "fine".
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        build_fixture(root / "inner", version=1, on_disk=[1])
        # Point the check at the tree without its .git, so ls-files fails.
        import shutil
        shutil.rmtree(root / "inner" / ".git")
        report = run(root / "inner")
        check("[no git] refuses to pass", len(report.problems) >= 1, True,
              f"-> {report.problems}")
        check("[no git] says why",
              any("git could not list" in p for p in report.problems), True)

    # --- parsing, without touching the filesystem ---
    src = Path("core/data/src/main/kotlin/x/Progress.kt")
    db, problem = parse_database(DB_SOURCE.format(version=6), src)
    check("[parse] no problem", problem, None)
    check("[parse] version", db.version if db else None, 6)
    check("[parse] fqn", db.fqn if db else None, FQN)
    check("[parse] schema dir", db.schema_dir.as_posix() if db else None,
          (Path("core/data/schemas") / FQN).as_posix())

    # `entities = [...]` holds brackets and `::class`; a non-greedy paren match would stop
    # inside `RoomDatabase()` and miss the version entirely.
    check("[parse] version survives the entities list",
          parse_database(DB_SOURCE.format(version=11), src)[0].version, 11)

    no_version = DB_SOURCE.format(version=2).replace("version = 2,", "")
    check("[parse] missing version is a problem",
          "no integer `version =`" in (parse_database(no_version, src)[1] or ""), True)

    check("[parse] a file with no @Database is skipped, not flagged",
          parse_database("package a\nclass B\n", src), (None, None))

    check("[module_root] above src/",
          module_root(Path("core/data/src/main/kotlin/a/B.kt")).as_posix(), "core/data")
    check("[module_root] no src/ is None", module_root(Path("a/B.kt")), None)

    total = passed + failed
    print(f"{'OK' if not failed else 'FAILED'}: {passed}/{total} cases pass")
    return 0 if not failed else 1


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=Path("."), help="repository root")
    ap.add_argument("--check", action="store_true",
                    help="run the self-test and exit; reads no repository source")
    ap.add_argument("--list", action="store_true",
                    help="print every database found, pass or fail")
    args = ap.parse_args(argv)

    if args.check:
        return self_test()

    report = run(args.root)

    if args.list:
        for db in report.databases:
            print(f"{db.fqn}  version={db.version}  {db.schema_dir}")

    # Scanning nothing is a broken gate, not a green one -- the same reason
    # check-strings.py exits 2 when it finds no sources.
    if not report.databases:
        print("check-schemas: found no @Database in the tree; the gate scanned nothing",
              file=sys.stderr)
        return 2

    if report.problems:
        for problem in report.problems:
            print(f"error: {problem}", file=sys.stderr)
        print(f"\n{len(report.problems)} problem(s) across "
              f"{len(report.databases)} database(s)", file=sys.stderr)
        return 1

    versions = sum(db.version for db in report.databases)
    print(f"OK: {len(report.databases)} database(s), {versions} exported schema(s) "
          f"present and tracked by git")
    return 0


if __name__ == "__main__":
    sys.exit(main())
