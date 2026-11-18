#!/usr/bin/env python3
"""Finds texts the user may see written into the Kotlin code instead of the string resources (keething-spec plans T13).

A string literal is flagged when it holds words: a capitalised word ("Saved"), or two words with a space between
("not saved"). Comments, KDoc, Log calls and require/check/error messages are skipped. A literal that is not a text the
user sees (a key, a tag, a name from the KDBX format) is kept by `// not user text` at the end of its line, or the line
before; a failure's technical detail, said in English after a translated message (keething-spec plans T13b, decided
2026-11-18), by `// English detail`. A whole file, or a folder (its path ending in /), is left out by being listed in
tools/texts-allowed.txt with the reason.

    tools/check-texts.py [dir ...]       default: app/ and every core module (DEFAULT_DIRS)
    tools/check-texts.py --count [dir]   only the number per file
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MARK = "not user text"
MARKS = (MARK, "English detail")
# app/ and every core module (keething-spec plans T13b).
DEFAULT_DIRS = ["app/src/main/kotlin"] + sorted(str(p.relative_to(ROOT)) for p in (ROOT / "core").glob("*/src/main/kotlin"))
# What a template puts in (${...} or $name): a word beside it is a text ("$count files"), holes alone are not ("$a $b").
HOLE = "\x01"
WORDS = re.compile(r"[A-Z][a-z]+|[A-Za-z]{2,} [A-Za-z]{2,}|\x01 [A-Za-z]{2,}|[A-Za-z]{2,} \x01")
# A key in camelCase ("pinLength", "useBiometric"): a name in a file or in settings, never words.
CAMEL_KEY = re.compile(r"[a-z][a-z0-9]*([A-Z][a-z0-9]*)+")
SKIPPED_CALL = re.compile(r"\b(Log\.[a-z]+|require|requireNotNull|check|checkNotNull|error|TODO|println|Exception|Error)\s*\($")
# The message of require(…) { "…" }, checkNotNull(…) { "…" }: for the programmer, as require("…") is.
LAZY_MESSAGE = re.compile(r"\b(require|requireNotNull|check|checkNotNull)\(.*\)\s*\{$")


def literals(text):
    """(line number, literal without its quotes, text before it on its line) for each string literal outside comments;
    a template's ${...} and $name are each a HOLE in it."""
    i, line, n = 0, 1, len(text)
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
            i += 1
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            line += text.count("\n", i, end)
            i = end
        elif c == "'":
            end = i + 1
            while end < n and text[end] != "'":
                end += 2 if text[end] == "\\" else 1
            i = end + 1
        elif c == '"':
            raw = text.startswith('"""', i)
            start_line, i = line, i + (3 if raw else 1)
            line_start = text.rfind("\n", 0, i) + 1
            before = text[line_start:i - (3 if raw else 1)]
            parts, depth = [], 0
            while i < n:
                if depth == 0 and (text.startswith('"""', i) if raw else text[i] == '"'):
                    i += 3 if raw else 1
                    break
                if not raw and depth == 0 and text[i] == "\\":
                    parts.append(text[i:i + 2])
                    i += 2
                    continue
                if text.startswith("${", i) and depth == 0:
                    depth, i = 1, i + 2
                    parts.append(HOLE)
                    continue
                if depth == 0 and text[i] == "$" and i + 1 < n and (text[i + 1].isalpha() or text[i + 1] == "_"):
                    i += 1
                    while i < n and (text[i].isalnum() or text[i] == "_"):
                        i += 1
                    parts.append(HOLE)
                    continue
                if depth:
                    depth += {"{": 1, "}": -1}.get(text[i], 0)
                else:
                    parts.append(text[i])
                if text[i] == "\n":
                    line += 1
                i += 1
            yield start_line, "".join(parts), before
        else:
            i += 1


def flagged(path):
    text = path.read_text()
    lines = text.split("\n")
    for number, literal, before in literals(text):
        if not WORDS.search(literal) or CAMEL_KEY.fullmatch(literal):
            continue
        here, previous = lines[number - 1], lines[number - 2] if number > 1 else ""
        if any(m in here or previous.strip().startswith("//") and m in previous for m in MARKS):
            continue
        if SKIPPED_CALL.search(before.rstrip()) or LAZY_MESSAGE.search(before.rstrip()) or re.search(r"\bLog\.[a-z]+\(", before):
            continue
        if re.match(r"\s*@", before):  # an annotation's argument
            continue
        yield number, literal


def main(argv):
    count = "--count" in argv
    dirs = [a for a in argv if not a.startswith("--")] or DEFAULT_DIRS
    allowed = {}
    allow_file = ROOT / "tools/texts-allowed.txt"
    if allow_file.exists():
        for row in allow_file.read_text().splitlines():
            if row.strip() and not row.startswith("#"):
                allowed[row.split()[0]] = row
    total = 0
    for d in dirs:
        for path in sorted((ROOT / d).rglob("*.kt")):
            rel = str(path.relative_to(ROOT))
            if rel in allowed or any(rel.startswith(folder) for folder in allowed if folder.endswith("/")):
                continue
            found = list(flagged(path))
            total += len(found)
            if count and found:
                print(f"{len(found):4} {rel}")
            elif not count:
                for number, literal in found:
                    print(f"{rel}:{number}: \"{literal.replace(HOLE, '$')}\"")
    if total:
        print(f"{total} texts in the code: put them in res/values/strings*.xml, or mark one `// {MARK}` (tools/check-texts.py)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
