#!/usr/bin/env python3
"""The accessibility tree of the screen the phone shows, checked (keething-spec app.accessibility AC1, AC2).

  tools/a11y-dump.py [--secret <text>]... [--tree] [--keep <file>]

Takes the tree with uiautomator (the app's screens are FLAG_SECURE: a screenshot is black, the tree is not) and flags,
for the app's nodes:
  - a node that acts (clickable, long-clickable, checkable) with no name: no text or content description of its own
    nor in a node inside it;
  - a node whose text or description is a secret: each --secret (default: the fixtures' password "pw"), exactly;
  - a password field whose text is anything but dots.
--tree prints every node of the app as the reader would meet it; --keep saves the XML. Exits 1 when anything is
flagged. One device: ANDROID_SERIAL when several are connected.
"""
import argparse, subprocess, sys
import xml.etree.ElementTree as ET

APP = "dev.keething"
DOTS = set("•●·*•")


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def dump() -> str:
    out = adb("shell", "uiautomator", "dump", "/sdcard/keething-a11y.xml")
    if "dumped to" not in out:
        sys.exit(f"uiautomator: {out.strip()}")
    try:
        return adb("exec-out", "cat", "/sdcard/keething-a11y.xml")
    finally:
        adb("shell", "rm", "-f", "/sdcard/keething-a11y.xml")


def own_name(node: ET.Element) -> str:
    return (node.get("text") or node.get("content-desc") or "").strip()


def name(node: ET.Element) -> str:
    return own_name(node) or next((n for n in (own_name(c) for c in node.iter()) if n), "")


def acts(node: ET.Element) -> bool:
    return any(node.get(a) == "true" for a in ("clickable", "long-clickable", "checkable"))


def where(node: ET.Element) -> str:
    return f"{node.get('class', '').rsplit('.', 1)[-1]} {node.get('bounds', '')}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--secret", action="append", help="a value the reader must never get (default: pw)")
    parser.add_argument("--tree", action="store_true", help="print the app's nodes")
    parser.add_argument("--keep", help="save the XML to this file")
    options = parser.parse_args()
    secrets = set(options.secret or ["pw"])

    xml = dump()
    if options.keep:
        with open(options.keep, "w") as file:
            file.write(xml)
    nodes = [n for n in ET.fromstring(xml).iter("node") if n.get("package", "").startswith(APP)]
    if not nodes:
        sys.exit("No node of the app on the screen (is it in front, unlocked?)")

    if options.tree:
        for node in nodes:
            text, desc = node.get("text") or "", node.get("content-desc") or ""
            if text or desc or acts(node):
                marks = "".join(m for m, a in (("C", "clickable"), ("L", "long-clickable"), ("K", "checkable"),
                                               ("P", "password")) if node.get(a) == "true")
                print(f"{marks:>3}  {where(node):<48} text={text!r} desc={desc!r}")
        print()

    problems = []
    for node in nodes:
        if acts(node) and not name(node):
            problems.append(f"no name: {where(node)}")
        for value in (node.get("text") or "", node.get("content-desc") or ""):
            if value.strip() in secrets:
                problems.append(f"a secret read aloud ({value.strip()!r}): {where(node)}")
        text = node.get("text") or ""
        if node.get("password") == "true" and text and not set(text) <= DOTS:
            problems.append(f"a password field gives its text ({len(text)} characters): {where(node)}")

    named = sum(1 for n in nodes if acts(n) and name(n))
    print(f"{len(nodes)} nodes, {named} that act and are named, {len(problems)} flagged")
    for problem in problems:
        print(f"  ✗ {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
