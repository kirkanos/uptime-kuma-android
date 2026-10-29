#!/usr/bin/env python3
"""Checks that every translatable string resource has a German translation.

For each module, all `src/<sourceSet>/res/values/*.xml` resources (`string`, `plurals`, `string-array`)
are compared with `values-de/`. Reports keys missing in German (error) and German keys without an
English original (warning, usually leftovers after a rename). Plural quantities are not compared:
German and English both use one/other.

Usage: python3 scripts/check-translations.py [--locale de] [--warn-only]
Exit code 1 when translations are missing (unless --warn-only).
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KINDS = ("string", "plurals", "string-array")
SKIP_DIRS = {"build", ".gradle", ".git", "node_modules", ".claude"}


def resource_keys(values_dir: Path) -> dict[str, Path]:
    keys: dict[str, Path] = {}
    if not values_dir.is_dir():
        return keys
    for xml_file in sorted(values_dir.glob("*.xml")):
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError as error:
            print(f"error: cannot parse {xml_file.relative_to(ROOT)}: {error}")
            sys.exit(2)
        for element in root:
            if element.tag not in KINDS:
                continue
            if element.get("translatable") == "false":
                continue
            name = element.get("name")
            if name:
                keys[f"{element.tag}/{name}"] = xml_file
    return keys


def res_dirs() -> list[Path]:
    found = []
    for path in ROOT.rglob("res"):
        if any(part in SKIP_DIRS for part in path.relative_to(ROOT).parts):
            continue
        if path.is_dir() and (path / "values").is_dir():
            found.append(path)
    return sorted(found)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--locale", default="de")
    parser.add_argument("--warn-only", action="store_true")
    args = parser.parse_args()

    missing_total = 0
    checked = 0
    for res in res_dirs():
        base = resource_keys(res / "values")
        if not base:
            continue
        checked += 1
        translated = resource_keys(res / f"values-{args.locale}")
        module = res.relative_to(ROOT)
        missing = sorted(set(base) - set(translated))
        extra = sorted(set(translated) - set(base))
        for key in missing:
            print(f"missing  {module}: {key}  ({base[key].name})")
        for key in extra:
            print(f"obsolete {module}: {key}  (only in values-{args.locale}/{translated[key].name})")
        missing_total += len(missing)
        status = "ok" if not missing else f"{len(missing)} missing"
        print(f"{status:>12}  {module}  ({len(base)} keys)")

    print(f"\n{checked} resource directories checked, {missing_total} {args.locale} translations missing.")
    return 1 if missing_total and not args.warn_only else 0


if __name__ == "__main__":
    sys.exit(main())
