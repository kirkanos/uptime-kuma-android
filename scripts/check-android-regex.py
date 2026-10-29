#!/usr/bin/env python3
"""Fails when a Regex literal in main sources contains an unescaped '{' or '}'.

The JVM accepts e.g. Regex("a}") but Android's ICU engine throws PatternSyntaxException at runtime,
so JVM unit tests cannot catch it (found in the release smoke test, 2026-10-29).
Quantifiers like {2} or {1,3} are allowed.
"""
import glob
import re
import sys

bad = []
for path in glob.glob("**/src/main/kotlin/**/*.kt", recursive=True):
    if "/build/" in path:
        continue
    source = open(path, encoding="utf-8").read()
    for m in re.finditer(r'Regex\(\s*("""(.*?)"""|"((?:[^"\\]|\\.)*)")', source, re.S):
        pattern = m.group(2) if m.group(2) is not None else m.group(3)
        stripped = re.sub(r"\{\d+(,\d*)?\}", "", pattern)
        stripped = stripped.replace("${'$'}", "")
        if any(c in "{}" and (i == 0 or stripped[i - 1] != "\\") for i, c in enumerate(stripped)):
            bad.append(f"{path}:{source[: m.start()].count(chr(10)) + 1}: {pattern}")

if bad:
    print("Unescaped braces in Regex literals (crash on Android):")
    print("\n".join(bad))
    sys.exit(1)
print("Regex literals OK")
