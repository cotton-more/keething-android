#!/usr/bin/env python3
"""Writes otp-formats.kdbx: a fictitious KDBX 4 database (password `pw`) with one-time codes in every format other
apps keep them in (keething-spec kdbx.otp-storage), for checking on a phone that each is read as the entry's code.

    tools/make-otp-database.py <output.kdbx>

KeePass 2.x's own TimeOtp-* fields in each of its four secret encodings (one broken: no code), KeePassXC's otp link
and the legacy TOTP Seed / TOTP Settings. Most of its codes are KeePass's, so a code written into it takes that format.
keepassxc-cli writes KDBX 3.1 on import, which the app refuses, so the import is merged into a copy of a KDBX 4
fixture (that adds the fixture's two entries).
"""
import base64
import os
import subprocess
import sys
import tempfile
from xml.sax.saxutils import escape

CLI = "/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli"
FIXTURE = os.path.join(os.path.dirname(__file__), "..", "fixtures", "kdbx4-chacha20-argon2id.kdbx")
RFC_SHA1 = "12345678901234567890"
RFC_SHA256 = "12345678901234567890123456789012"

ENTRIES = [
    ("KeePass code, Base32 (fictitious)", [
        ("TimeOtp-Secret-Base32", "JBSWY3DPEHPK3PXP", True), ("TimeOtp-Length", "6"), ("TimeOtp-Period", "30"),
        ("TimeOtp-Algorithm", "HMAC-SHA-1")]),
    ("KeePass code, hex, SHA-256, 8 digits (fictitious)", [
        ("TimeOtp-Secret-Hex", RFC_SHA256.encode().hex(), True), ("TimeOtp-Length", "8"),
        ("TimeOtp-Algorithm", "HMAC-SHA-256")]),
    ("KeePass code, text, 60 s (fictitious)", [("TimeOtp-Secret", RFC_SHA1, True), ("TimeOtp-Period", "60")]),
    ("KeePass code, Base64 (fictitious)", [("TimeOtp-Secret-Base64", base64.b64encode(RFC_SHA1.encode()).decode(), True)]),
    ("KeePass code, broken (fictitious)", [("TimeOtp-Secret-Hex", "zz", True), ("Note", "the only field shown")]),
    ("KeePassXC link (fictitious)", [("otp", "otpauth://totp/Link:me?secret=GEZDGNBVGY3TQOJQ&period=30&digits=7&issuer=Link", True)]),
    ("Legacy seed (fictitious)", [("TOTP Seed", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", True), ("TOTP Settings", "30;8")]),
    ("No code yet (fictitious)", []),
]


def uuid():
    return base64.b64encode(os.urandom(16)).decode()


def field(key, value, protected=False):
    attribute = ' ProtectInMemory="True"' if protected else ""
    return f"<String><Key>{escape(key)}</Key><Value{attribute}>{escape(value)}</Value></String>"


def xml():
    entries = "".join(
        f"<Entry><UUID>{uuid()}</UUID>{field('Title', title)}{field('UserName', 'me')}{field('Password', 'Otp-Pa55!', True)}"
        f"{''.join(field(*f) for f in fields)}</Entry>"
        for title, fields in ENTRIES
    )
    return ('<?xml version="1.0" encoding="utf-8" standalone="yes"?><KeePassFile><Meta><Generator>test</Generator>'
            f'<DatabaseName>One-time code formats</DatabaseName></Meta><Root><Group><UUID>{uuid()}</UUID>'
            f'<Name>One-time code formats</Name><Group><UUID>{uuid()}</UUID><Name>Codes</Name>{entries}</Group></Group>'
            '</Root></KeePassFile>')


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "otp.xml")
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
