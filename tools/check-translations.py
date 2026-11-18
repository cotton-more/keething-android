#!/usr/bin/env python3
"""Checks each translation in app/src/main/res/values-<language>/ against the English in values/ (keething-spec plans T14).

A translated file has the English file's name. It fails on:
- a resource the English does not have (renamed or removed there), or one of another kind (a string for a plural);
- a placeholder (%1$s, %2$d, %d, %1$.1f) the English has and the translation does not, or the other way round; in a plural,
  every quantity of the translation has the placeholders of the English "other" (Russian "one" is also 21, 31 …);
- a plural without the quantities its language needs (QUANTITIES), or with one it does not use;
- res/xml/locales_config.xml (the phone's per-app language) not naming English and every translated language.

A resource the translation does not have yet falls back to English on the phone; it is counted, and with --complete
it fails too.

    tools/check-translations.py              the counts, and what is wrong
    tools/check-translations.py --complete   also fails on a resource not translated
    tools/check-translations.py --missing    lists the resources not translated
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
# The plural quantities each language uses (CLDR), all of which a translation gives.
QUANTITIES = {"ru": {"one", "few", "many", "other"}}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?,?(?:\.\d+)?[sdf]")


def resources(path):
    """name -> ("string", text) or ("plurals", {quantity: text})."""
    found = {}
    for element in ET.parse(path).getroot():
        name = element.get("name")
        if element.tag == "string":
            found[name] = ("string", "".join(element.itertext()))
        elif element.tag == "plurals":
            found[name] = ("plurals", {item.get("quantity"): "".join(item.itertext()) for item in element})
    return found


def placeholders(text):
    return sorted(PLACEHOLDER.findall(text))


def check(language, folder, complete, list_missing):
    problems, missing, total = [], [], 0
    english = {path.name: resources(path) for path in sorted((RES / "values").glob("strings*.xml"))}
    for path in sorted(folder.glob("strings*.xml")):
        if path.name not in english:
            problems.append(f"{path.name}: no English file of this name")
    for file, originals in english.items():
        path = folder / file
        translated = resources(path) if path.exists() else {}
        where = f"{folder.name}/{file}"
        for name in translated.keys() - originals.keys():
            problems.append(f"{where}: {name}: not in the English")
        for name, (kind, text) in originals.items():
            total += 1
            if name not in translated:
                missing.append(f"{where}: {name}")
                continue
            their_kind, their_text = translated[name]
            if their_kind != kind:
                problems.append(f"{where}: {name}: a {their_kind}, the English is a {kind}")
            elif kind == "string":
                if placeholders(text) != placeholders(their_text):
                    problems.append(f"{where}: {name}: placeholders {placeholders(their_text)}, "
                                    f"the English {placeholders(text)}")
            else:
                needed = QUANTITIES.get(language)
                if needed is not None and set(their_text) != needed:
                    problems.append(f"{where}: {name}: quantities {sorted(their_text)}, {language} needs {sorted(needed)}")
                wanted = placeholders(text.get("other", ""))
                for quantity, item in their_text.items():
                    if placeholders(item) != wanted:
                        problems.append(f"{where}: {name} ({quantity}): placeholders {placeholders(item)}, "
                                        f"the English {wanted}")
    print(f"{folder.name}: {total - len(missing)} of {total} translated")
    if list_missing:
        print("\n".join(f"  not translated: {line}" for line in missing))
    if complete and missing:
        problems.append(f"{folder.name}: {len(missing)} not translated (--missing lists them)")
    return problems


def main(args):
    problems, languages = [], {"en"}
    for folder in sorted(RES.glob("values-*")):
        language = folder.name.removeprefix("values-")
        if not re.fullmatch(r"[a-z]{2,3}(-r[A-Z]{2})?", language) or not any(folder.glob("strings*.xml")):
            continue
        languages.add(language.replace("-r", "-"))
        problems += check(language.split("-")[0], folder, "--complete" in args, "--missing" in args)
    android = "{http://schemas.android.com/apk/res/android}name"
    offered = {locale.get(android) for locale in ET.parse(RES / "xml/locales_config.xml").getroot()}
    if offered != languages:
        problems.append(f"xml/locales_config.xml: offers {sorted(offered)}, the translations are {sorted(languages)}")
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
