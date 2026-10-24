#!/usr/bin/env python3
"""Writes autofill.kdbx: a fictitious KDBX 4 database (password `pw`) for checking autofill on a phone.

    tools/autofill-test/make-database.py <output.kdbx>

Its entries match the pages in pages/ (served at http://localhost:8000 through `adb reverse`), a few
real sites and apps by their verified websites, and Keep by `androidapp://`; two Wi-Fi networks (one open)
check the card's QR code, typed by the "Keething" entry. keepassxc-cli writes KDBX 3.1
on import, which the app refuses, so the import is merged into a copy of a KDBX 4 fixture (that adds the
fixture's two entries).
"""
import base64
import os
import subprocess
import sys
import tempfile
from xml.sax.saxutils import escape

CLI = "/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli"
FIXTURE = os.path.join(os.path.dirname(__file__), "..", "..", "fixtures", "kdbx4-chacha20-argon2id.kdbx")

ENTRIES = [
    ("Local test page", "alice@example.test", "Local-Pa55!", "http://localhost:8000",
     [("otp", "otpauth://totp/Local:alice?secret=JBSWY3DPEHPK3PXP&issuer=Local")]),
    ("Local, second account", "bob", "Bob-Pa55!", "http://localhost:8000/login", []),
    ("Local over https only", "carol", "Carol-Pa55!", "https://localhost:8000", []),
    ("GitHub (fictitious)", "octo-test", "Gh-Pa55!", "https://github.com", []),
    ("Lidl (fictitious)", "lidl-test@example.test", "Lidl-Pa55!", "https://www.lidl.de", []),
    ("MTS (fictitious)", "+79990000000", "Mts-Pa55!", "https://lk.mts.ru", []),
    ("Firefox account (fictitious)", "fx-test@example.test", "Fx-Pa55!", "https://accounts.firefox.com", []),
    ("Keep by package (fictitious)", "keep-user", "Keep-Pa55!", "androidapp://com.google.android.keep", []),
    ("Extra URL entry (fictitious)", "extra-user", "Extra-Pa55!", "https://example.org",
     [("KP2A_URL", "https://github.com/login")]),
    ("Unrelated (fictitious)", "nobody", "Nope-Pa55!", "https://example.net", []),
    ("Google account (fictitious)", "g-test@example.test", "Goo-Pa55!", "https://accounts.google.com", []),
    ("Home Wi-Fi (fictitious)", "", "", "",
     [("SSID", "Example;Net"), ("Wi-Fi password", "wifi-Pa55:word", True), ("Security", "WPA2")]),
    ("Cafe Wi-Fi (fictitious)", "", "", "", [("SSID", "Cafe Guest")]),
    ("Keething", "", "", "", [("Notes", "SSID = wifi.ssid\nWi-Fi password = wifi.password\nSecurity = wifi.security")]),
]


def uuid():
    return base64.b64encode(os.urandom(16)).decode()


def field(key, value, protected=False):
    attribute = ' ProtectInMemory="True"' if protected else ""
    return f"<String><Key>{escape(key)}</Key><Value{attribute}>{escape(value)}</Value></String>"


def xml():
    entries = "".join(
        f"<Entry><UUID>{uuid()}</UUID>{field('Title', t)}{field('UserName', u)}{field('Password', p)}"
        f"{field('URL', url)}{''.join(field(*f) for f in extra)}</Entry>"
        for t, u, p, url, extra in ENTRIES
    )
    return ('<?xml version="1.0" encoding="utf-8" standalone="yes"?><KeePassFile><Meta><Generator>test</Generator>'
            f'<DatabaseName>Autofill test</DatabaseName></Meta><Root><Group><UUID>{uuid()}</UUID><Name>Autofill test</Name>'
            f'<Group><UUID>{uuid()}</UUID><Name>Logins</Name>{entries}</Group></Group></Root></KeePassFile>')


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "autofill.xml")
        imported = os.path.join(tmp, "imported.kdbx")
        with open(source, "w") as f:
            f.write(xml())
        subprocess.run([CLI, "import", "-q", "-p", source, imported], input=b"pw\npw\n", check=True)
        with open(FIXTURE, "rb") as src, open(out, "wb") as dst:
            dst.write(src.read())
        subprocess.run([CLI, "merge", "-q", "-s", out, imported], input=b"pw\n", check=True)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
