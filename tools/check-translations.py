#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify translated string resources against the English source (i18n milestone 5).

    python3 tools/check-translations.py            # the gate
    python3 tools/check-translations.py --check    # self-test; reads no repository resource
    python3 tools/check-translations.py --list     # every locale found, and what was compared
    python3 tools/check-translations.py --refresh-cldr   # re-pin the CLDR plural data (network)

Why this exists, before any translation does
--------------------------------------------
A bad translation does not look like a bug in review. ``"Page %1$d of %2$d"`` coming back as
``"Seite %1$d"`` reads fine in a diff and throws ``MissingFormatArgumentException`` on a device;
a Polish plural missing ``few`` silently renders the ``other`` form for 2, 3 and 4. Neither is
visible to anyone who does not read the language. So the gate lands first, exactly as
``check-strings.py`` landed before externalisation.

What is a failure, and what is not
----------------------------------
**Not** a failure: a string English has and a translation does not. Android falls back to
English per-resource, which is the designed behaviour and the normal state of a translation in
progress. Recording which locales are complete enough to ship is the review-status list's job,
not this gate's.

Failures are the three ways a *present* translation is wrong:

1. **Placeholders disagree with English.** Same positions, same conversion types, no extras.
2. **A plural lacks a form its language requires.** Not the same set everywhere: English needs
   ``one``/``other``, Polish ``one``/``few``/``many``/``other``, Arabic all six.
3. **The key should not be there.** It does not exist in English (a leftover from a renamed
   string), or English marks it ``translatable="false"``.

Where the plural forms come from
--------------------------------
``tools/cldr-plural-forms.json``, derived from CLDR's own supplemental data — not typed out
here. That file records the package, version, URL and SHA-256 it came from, and
``--refresh-cldr`` regenerates it from the same source so the provenance is executable rather
than a comment. Hand-maintaining that table is how a language quietly gets the wrong rules:
French gained a ``many`` category in recent CLDR, and nobody would have noticed.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

CLDR_DATA = Path("tools/cldr-plural-forms.json")
CLDR_PACKAGE = "cldr-core"
CLDR_VERSION = "48.2.0"
CLDR_URL = f"https://registry.npmjs.org/{CLDR_PACKAGE}/-/{CLDR_PACKAGE}-{CLDR_VERSION}.tgz"
CLDR_MEMBER = "package/supplemental/plurals.json"
CLDR_TARBALL_SHA256 = "5310e0c7a06c1feb83dc8e54c8584bbe0b9c2ea8320172a18d541986b124585d"

# A format specifier, or a literal "%%". Group 1 is set only for "%%"; otherwise group 2 is the
# 1-based argument index when the specifier is positional, and group 3 the conversion letter.
# Flags, width and precision are skipped: "%1$,.2f" and "%-10s" are the same argument as "%s".
PLACEHOLDER = re.compile(r"%(?:(%)|(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z]))")

# An Android resource qualifier naming a language: "de", "zh-rCN", or BCP-47 "b+sr+Latn".
LANG_ONLY = re.compile(r"^[a-z]{2,3}$")
REGION = re.compile(r"^(?:r[A-Z]{2}|\d{3})$")


class Finding:
    """One way a translation is wrong, with enough detail to fix it without guessing."""

    __slots__ = ("path", "locale", "kind", "name", "detail")

    def __init__(self, path: str, locale: str, kind: str, name: str, detail: str) -> None:
        self.path, self.locale, self.kind = path, locale, kind
        self.name, self.detail = name, detail

    def __str__(self) -> str:
        return f"{self.path}: [{self.kind}] {self.name}: {self.detail}"


# --------------------------------------------------------------------------------------
# CLDR plural categories
# --------------------------------------------------------------------------------------


def fetch_cldr() -> dict:
    """Download the pinned CLDR tarball, verify it, and reduce it to cardinal categories."""
    with urllib.request.urlopen(CLDR_URL) as response:      # noqa: S310 - pinned https URL
        blob = response.read()
    digest = hashlib.sha256(blob).hexdigest()
    if digest != CLDR_TARBALL_SHA256:
        raise SystemExit(f"CLDR tarball sha256 {digest} != pinned {CLDR_TARBALL_SHA256}")
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
        member = tar.extractfile(CLDR_MEMBER)
        if member is None:
            raise SystemExit(f"{CLDR_MEMBER} missing from {CLDR_PACKAGE}-{CLDR_VERSION}")
        supplemental = json.load(member)["supplemental"]
    cardinal = {
        locale: sorted(rule.replace("pluralRule-count-", "") for rule in rules)
        for locale, rules in supplemental["plurals-type-cardinal"].items()
    }
    return {
        "_source": {
            "package": f"{CLDR_PACKAGE}@{CLDR_VERSION}",
            "url": CLDR_URL,
            "member": CLDR_MEMBER,
            "tarball_sha256": CLDR_TARBALL_SHA256,
            "cldr_version": str(supplemental["version"].get("_cldrVersion", "")),
            "contains": "cardinal plural categories per locale; the rule expressions are not "
                        "kept because this tool only needs which forms must exist",
            "regenerate": "python3 tools/check-translations.py --refresh-cldr",
        },
        "cardinal": dict(sorted(cardinal.items())),
    }


def load_cldr(root: Path) -> dict[str, list[str]]:
    path = root / CLDR_DATA
    if not path.exists():
        raise SystemExit(f"{CLDR_DATA} missing; regenerate with --refresh-cldr")
    return json.loads(path.read_text(encoding="utf-8"))["cardinal"]


def plural_forms(locale: str, cardinal: dict[str, list[str]]) -> list[str] | None:
    """Required cardinal forms for a locale, matching CLDR's own key fallback.

    CLDR keys are mostly bare languages but sometimes carry a region ("pt-PT"), so the lookup
    walks from the most specific tag to the bare language rather than assuming either shape.
    """
    parts = locale.split("-")
    for end in range(len(parts), 0, -1):
        key = "-".join(parts[:end])
        if key in cardinal:
            return cardinal[key]
    return None


# --------------------------------------------------------------------------------------
# Android resources
# --------------------------------------------------------------------------------------


def locale_of(values_dir: str) -> str | None:
    """The BCP-47 locale a `values-*` directory targets, or None if it targets something else.

    `values-night` and `values-sw600dp` are configuration qualifiers, not languages, and a
    locale qualifier is always the first one — so anything whose first segment is not a language
    is skipped rather than guessed at.
    """
    if not values_dir.startswith("values-"):
        return None
    parts = values_dir[len("values-"):].split("-")
    if parts[0].startswith("b+"):
        return "-".join(parts[0][2:].split("+"))
    if not LANG_ONLY.match(parts[0]):
        return None
    locale = parts[0]
    if len(parts) > 1 and REGION.match(parts[1]):
        locale += "-" + parts[1].removeprefix("r")
    return locale


def parse_strings(text: str) -> tuple[dict[str, str], dict[str, dict[str, str]], set[str]]:
    """Return (strings, plurals, untranslatable) from one strings.xml.

    `plurals` maps a name to {quantity: text}. `untranslatable` holds the names marked
    translatable="false", which only matters in the English source.
    """
    root = ET.fromstring(text)
    strings: dict[str, str] = {}
    plurals: dict[str, dict[str, str]] = {}
    untranslatable: set[str] = set()
    for element in root:
        name = element.get("name")
        if not name:
            continue
        if element.get("translatable") == "false":
            untranslatable.add(name)
        if element.tag == "string":
            strings[name] = "".join(element.itertext())
        elif element.tag == "plurals":
            plurals[name] = {
                item.get("quantity", ""): "".join(item.itertext())
                for item in element
                if item.tag == "item"
            }
    return strings, plurals, untranslatable


def placeholders(text: str) -> tuple[dict[str, str], list[str]]:
    """(positional {index: conversion}, non-positional [conversions in order]) in `text`."""
    positional: dict[str, str] = {}
    sequential: list[str] = []
    for literal, index, conversion in PLACEHOLDER.findall(text):
        if literal:                       # "%%" is a percent sign, not an argument
            continue
        if index:
            positional[index] = conversion
        else:
            sequential.append(conversion)
    return positional, sequential


# --------------------------------------------------------------------------------------
# The three checks
# --------------------------------------------------------------------------------------

KIND_PLACEHOLDER = "placeholder"
KIND_PLURAL = "plural"
KIND_STALE = "stale-key"
KIND_UNTRANSLATABLE = "not-translatable"
KIND_LOCALE = "unknown-locale"
KIND_REFERENCE = "english-reference"


def placeholder_problems(english: str, translated: str) -> list[str]:
    """How `translated`'s format arguments disagree with `english`'s, if at all."""
    problems: list[str] = []
    english_positional, english_sequential = placeholders(english)
    translated_positional, translated_sequential = placeholders(translated)
    for index in sorted(set(english_positional) | set(translated_positional), key=int):
        want = english_positional.get(index)
        got = translated_positional.get(index)
        if want and not got:
            problems.append(f"drops %{index}${want}")
        elif got and not want:
            problems.append(f"adds %{index}${got}, which English does not supply")
        elif want != got:
            problems.append(f"%{index}$ is {want} in English but {got} here")
    if english_sequential != translated_sequential:
        problems.append(
            f"unindexed placeholders are {english_sequential or 'none'} in English "
            f"but {translated_sequential or 'none'} here"
        )
    return problems


def check_translation(
    path: str,
    locale: str,
    english: tuple[dict[str, str], dict[str, dict[str, str]], set[str]],
    translated: tuple[dict[str, str], dict[str, dict[str, str]], set[str]],
    cardinal: dict[str, list[str]],
) -> list[Finding]:
    """Every way one locale's strings.xml is wrong against the English source."""
    english_strings, english_plurals, untranslatable = english
    strings, plurals, _ = translated
    findings: list[Finding] = []

    def report(kind: str, name: str, detail: str) -> None:
        findings.append(Finding(path, locale, kind, name, detail))

    forms = plural_forms(locale, cardinal)
    if forms is None:
        report(KIND_LOCALE, locale, "no CLDR plural data — check the directory qualifier")

    for name, text in sorted(strings.items()):
        if name in untranslatable:
            report(KIND_UNTRANSLATABLE, name, 'English marks this translatable="false"')
            continue
        if name not in english_strings:
            report(KIND_STALE, name, "no such string in English; renamed or removed")
            continue
        for problem in placeholder_problems(english_strings[name], text):
            report(KIND_PLACEHOLDER, name, problem)

    for name, quantities in sorted(plurals.items()):
        if name in untranslatable:
            report(KIND_UNTRANSLATABLE, name, 'English marks this translatable="false"')
            continue
        if name not in english_plurals:
            report(KIND_STALE, name, "no such plurals in English; renamed or removed")
            continue
        if forms is not None:
            missing = [form for form in forms if form not in quantities]
            if missing:
                report(
                    KIND_PLURAL, name,
                    f"missing {', '.join(missing)} — {locale} requires {', '.join(forms)}",
                )
        # "other" is the form every language has, so it is the reference for the arguments a
        # plural takes; a form that quietly drops one crashes only for the counts that select it.
        reference = english_plurals[name].get("other", "")
        for quantity, text in sorted(quantities.items()):
            for problem in placeholder_problems(reference, text):
                report(KIND_PLACEHOLDER, f"{name}[{quantity}]", problem)

    return findings


def check_english(path: str, plurals: dict[str, dict[str, str]],
                  cardinal: dict[str, list[str]]) -> list[Finding]:
    """The English source has to be valid for any comparison against it to mean anything.

    Only its plurals are checked: if `other` is absent there is no reference for the argument
    comparison above, and every locale's result would be quietly wrong rather than failing.
    """
    forms = plural_forms("en", cardinal) or []
    findings: list[Finding] = []
    for name, quantities in sorted(plurals.items()):
        missing = [form for form in forms if form not in quantities]
        if missing:
            findings.append(Finding(
                path, "en", KIND_REFERENCE, name,
                f"missing {', '.join(missing)} — en requires {', '.join(forms)}",
            ))
    return findings


def english_sources(root: Path) -> list[Path]:
    """Every module's English strings.xml. Modules sit one or two directories deep."""
    found = list(root.glob("*/src/main/res/values/strings.xml"))
    found += list(root.glob("*/*/src/main/res/values/strings.xml"))
    return sorted(found)


def scan_tree(root: Path, cardinal: dict[str, list[str]]) -> tuple[list[Finding], list[str]]:
    """(findings, one line per comparison made) over every module in the tree."""
    findings: list[Finding] = []
    compared: list[str] = []
    for english_path in english_sources(root):
        english = parse_strings(english_path.read_text(encoding="utf-8"))
        rel_english = english_path.relative_to(root).as_posix()
        findings += check_english(rel_english, english[1], cardinal)
        res_dir = english_path.parent.parent
        for values_dir in sorted(res_dir.iterdir()):
            locale = locale_of(values_dir.name) if values_dir.is_dir() else None
            if locale is None:
                continue
            strings_xml = values_dir / "strings.xml"
            if not strings_xml.is_file():
                continue
            rel = strings_xml.relative_to(root).as_posix()
            translated = parse_strings(strings_xml.read_text(encoding="utf-8"))
            compared.append(f"{rel}  ({locale}: {len(translated[0])} strings, "
                            f"{len(translated[1])} plurals)")
            findings += check_translation(rel, locale, english, translated, cardinal)
    return findings, compared


# --------------------------------------------------------------------------------------
# Self-test
#
# Fixtures rather than repository resources: the gate has to keep working when no translation
# exists (which is today) and when every one of them is correct (which is the goal), and a
# check asserted only against real files stops being asserted the moment they are right.
# The pinned CLDR data is loaded, because it is this tool's own input rather than a thing
# under test.
# --------------------------------------------------------------------------------------

def _xml(body: str) -> str:
    return f'<?xml version="1.0" encoding="utf-8"?><resources>{body}</resources>'


_EN_PAGE = _xml('<string name="page">Page %1$d of %2$d</string>')
_EN_PLURAL = _xml('<plurals name="pages"><item quantity="one">%1$d page</item>'
                  '<item quantity="other">%1$d pages</item></plurals>')

FIXTURES: tuple[tuple[str, str, str, str, set[tuple[str, str]]], ...] = (
    ("matching placeholders are clean", _EN_PAGE, "de",
     _xml('<string name="page">Seite %1$d von %2$d</string>'), set()),
    ("a dropped positional argument", _EN_PAGE, "de",
     _xml('<string name="page">Seite %1$d</string>'), {(KIND_PLACEHOLDER, "page")}),
    ("an added positional argument", _EN_PAGE, "de",
     _xml('<string name="page">Seite %1$d von %2$d, %3$s</string>'),
     {(KIND_PLACEHOLDER, "page")}),
    ("a changed conversion type", _EN_PAGE, "de",
     _xml('<string name="page">Seite %1$s von %2$d</string>'), {(KIND_PLACEHOLDER, "page")}),
    ("reordering positional arguments is fine", _EN_PAGE, "de",
     _xml('<string name="page">Von %2$d: Seite %1$d</string>'), set()),
    ("a doubled percent is a percent sign, not an argument",
     _xml('<string name="pct">%1$d%% done</string>'), "de",
     _xml('<string name="pct">%1$d%% fertig</string>'), set()),
    ("unindexed placeholders must match in order and type",
     _xml('<string name="two">%s and %d</string>'), "de",
     _xml('<string name="two">%s und %s</string>'), {(KIND_PLACEHOLDER, "two")}),
    ("a missing translation is not a failure",
     _xml('<string name="a">A</string><string name="b">B</string>'), "de",
     _xml('<string name="a">A</string>'), set()),
    ("a key English does not have is stale",
     _xml('<string name="a">A</string>'), "de",
     _xml('<string name="a">A</string><string name="gone">Weg</string>'),
     {(KIND_STALE, "gone")}),
    ("a key English marks untranslatable",
     _xml('<string name="brand" translatable="false">Absolutex</string>'), "de",
     _xml('<string name="brand">Absolutex</string>'), {(KIND_UNTRANSLATABLE, "brand")}),
    # Plural forms, straight from CLDR: these four locales need four different sets.
    ("polish needs few", _EN_PLURAL, "pl",
     _xml('<plurals name="pages"><item quantity="one">%1$d strona</item>'
          '<item quantity="many">%1$d stron</item>'
          '<item quantity="other">%1$d strony</item></plurals>'),
     {(KIND_PLURAL, "pages")}),
    ("arabic with all six forms is clean", _EN_PLURAL, "ar",
     _xml('<plurals name="pages">'
          + "".join(f'<item quantity="{q}">%1$d</item>'
                    for q in ("zero", "one", "two", "few", "many", "other"))
          + "</plurals>"), set()),
    ("japanese needs only other", _EN_PLURAL, "ja",
     _xml('<plurals name="pages"><item quantity="other">%1$d ページ</item></plurals>'), set()),
    ("german missing one", _EN_PLURAL, "de",
     _xml('<plurals name="pages"><item quantity="other">%1$d Seiten</item></plurals>'),
     {(KIND_PLURAL, "pages")}),
    ("a plural form that drops its argument", _EN_PLURAL, "de",
     _xml('<plurals name="pages"><item quantity="one">eine Seite</item>'
          '<item quantity="other">%1$d Seiten</item></plurals>'),
     {(KIND_PLACEHOLDER, "pages[one]")}),
    # A literal percent is not an argument, so English keeping one and a translation not is a
    # wording choice, not a format error. The symmetric case cannot show this: miscounting "%%"
    # on both sides cancels out, and a mutation test caught the fixture being that weak.
    ("dropping a literal percent sign is not a placeholder change",
     _xml('<string name="pct">%1$d%% done</string>'), "de",
     _xml('<string name="pct">%1$d Prozent fertig</string>'), set()),
    # CLDR keys are languages, sometimes with a region, and "pt-BR"/"zh-CN" are neither — they
    # resolve to "pt" and "zh". values-pt-rBR and values-zh-rCN are ordinary Android qualifiers,
    # so a lookup without that fallback breaks on the most common regional directories there are.
    ("a regional locale takes its language's forms", _EN_PLURAL, "pt-BR",
     _xml('<plurals name="pages"><item quantity="one">%1$d p\u00e1gina</item>'
          '<item quantity="many">%1$d de p\u00e1ginas</item>'
          '<item quantity="other">%1$d p\u00e1ginas</item></plurals>'), set()),
    ("a regional locale needing only other", _EN_PLURAL, "zh-CN",
     _xml('<plurals name="pages"><item quantity="other">%1$d \u9875</item></plurals>'), set()),
    ("a locale CLDR does not know", _EN_PAGE, "zz",
     _xml('<string name="page">Page %1$d of %2$d</string>'), {(KIND_LOCALE, "zz")}),
)

QUALIFIERS: tuple[tuple[str, str | None], ...] = (
    ("values-de", "de"),
    ("values-zh-rCN", "zh-CN"),
    ("values-pt-rBR", "pt-BR"),
    ("values-b+sr+Latn", "sr-Latn"),
    ("values-fil", "fil"),
    ("values", None),
    ("values-night", None),
    ("values-sw600dp", None),
    ("values-v33", None),
)


def self_test(root: Path) -> int:
    cardinal = load_cldr(root)
    failures = 0
    for name, english_xml, locale, translated_xml, expected in FIXTURES:
        actual = {
            (f.kind, f.name)
            for f in check_translation(
                "fixture.xml", locale,
                parse_strings(english_xml), parse_strings(translated_xml), cardinal,
            )
        }
        if actual != expected:
            failures += 1
            print(f"FAIL  {name}")
            for missing in sorted(expected - actual):
                print(f"        expected, not found: {missing}")
            for extra in sorted(actual - expected):
                print(f"        found, not expected: {extra}")

    for qualifier, expected_locale in QUALIFIERS:
        actual_locale = locale_of(qualifier)
        if actual_locale != expected_locale:
            failures += 1
            print(f"FAIL  qualifier {qualifier}: expected {expected_locale}, got {actual_locale}")

    reference = check_english(
        "values/strings.xml",
        parse_strings(_xml('<plurals name="p"><item quantity="one">x</item></plurals>'))[1],
        cardinal,
    )
    if not reference:
        failures += 1
        print("FAIL  an English plural missing 'other' must be reported")

    total = len(FIXTURES) + len(QUALIFIERS) + 1
    if failures:
        print(f"\n{failures} of {total} cases failed")
        return 1
    print(f"OK: {total} cases pass ({len(FIXTURES)} translation, "
          f"{len(QUALIFIERS)} qualifier, 1 English reference)")
    return 0


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=Path("."), help="repository root")
    ap.add_argument("--check", action="store_true", help="run the self-test and exit")
    ap.add_argument("--list", action="store_true", help="print every comparison made")
    ap.add_argument("--refresh-cldr", action="store_true",
                    help=f"re-download {CLDR_PACKAGE}@{CLDR_VERSION} and rewrite {CLDR_DATA}")
    ap.add_argument("--summary", type=Path, default=None,
                    help="append a Markdown report here (GITHUB_STEP_SUMMARY)")
    args = ap.parse_args(argv)

    if args.refresh_cldr:
        data = fetch_cldr()
        target = args.root / CLDR_DATA
        target.write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"{target}: {len(data['cardinal'])} locales from {data['_source']['package']} "
              f"(CLDR {data['_source']['cldr_version']})")
        return 0

    if args.check:
        return self_test(args.root)

    cardinal = load_cldr(args.root)
    sources = english_sources(args.root)
    if not sources:
        print(f"error: no English strings.xml found under {args.root}. "
              "A gate that compares nothing is not a gate.")
        return 2

    findings, compared = scan_tree(args.root, cardinal)

    if args.list:
        for line in compared:
            print(f"  compared  {line}")
        if not compared:
            print("  (no translated locales yet)")

    for finding in findings:
        print(finding)

    if args.summary:
        lines = ["### Translations\n",
                 f"{len(sources)} English source(s) · {len(compared)} translated locale file(s) "
                 f"· **{len(findings)} finding(s)**\n"]
        lines += [f"- `{f.path}` [{f.kind}] `{f.name}` — {f.detail}" for f in findings]
        with args.summary.open("a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")

    if findings:
        print(f"\n{len(findings)} finding(s) across {len(compared)} translated file(s).")
        print("A missing string is not one of them — Android falls back to English per "
              "resource. These are translations that are present and wrong.")
        return 1

    if not compared:
        print(f"OK: {len(sources)} English source(s), no translated locales yet — "
              "nothing to compare, and that is the expected state before milestone 5.")
        return 0
    print(f"OK: {len(compared)} translated file(s) across {len(sources)} module(s) agree "
          "with English")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
