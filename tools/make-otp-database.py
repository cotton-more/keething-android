#!/usr/bin/env python3
"""Writes otp-formats.kdbx: a fictitious KDBX 4 database (password `pw`) with one-time codes in every format other
apps keep them in (keething-spec kdbx.otp-storage), for checking on a phone that each is read as the entry's code.

    tools/make-otp-database.py <output.kdbx> [--recycle-bin-off]

KeePass 2.x's own TimeOtp-* fields in each of its four secret encodings (one broken: no code), KeePassXC's otp link
and the legacy TOTP Seed / TOTP Settings; further codes (custom fields holding an otpauth link, keething-spec
app.codes-view), beside an entry's own code and on their own; two entries with nothing but a title and a code. Most of its codes are KeePass's, so a code written into it takes that format.
Codes the app cannot use, each shown with its reason (keething-spec card.totp AC6): Steam as KeePassXC writes it
(`encoder=steam`) and in the legacy fields (`30;S`), a counter-based link, an unknown algorithm. A list of recovery
codes typed `code` (one used, one empty) beside an empty field (card.recovery-codes, card.fields AC2).
keepassxc-cli writes KDBX 3.1 on import, so the import is merged into a copy of a KDBX 4 fixture (that adds the
fixture's two entries). With --recycle-bin-off the import itself is written, KDBX 3.1 with `RecycleBinEnabled` False
(a merge does not carry Meta; keepassxc-cli has no switch for it): a row's long press then offers no Move to the
recycle bin (keething-spec edit.delete-for-good). A KDBX 3.1 file holds no field types, so the recovery codes are
plain fields there.
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
    ("Further codes (fictitious)", [
        ("otp", "otpauth://totp/Further:me?secret=JBSWY3DPEHPK3PXP&issuer=Further", True),
        ("One-time code 2", "otpauth://totp/Further:second?secret=GEZDGNBVGY3TQOJQ&issuer=Further", True),
        ("Work account", "otpauth://totp/Further:work?secret=MFRGGZDFMZTWQ2LK&period=60&issuer=Further")]),
    ("Further code only (fictitious)", [("Robot", "otpauth://totp/Only:robot?secret=KRSXG5CTMVRXEZLU&issuer=Only", True)]),
    # More codes than a phone's screen holds: the row's sheet of codes scrolls (keething-spec search.row-codes AC4).
    ("Many codes (fictitious)", [("otp", "otpauth://totp/Many:me?secret=JBSWY3DPEHPK3PXP&issuer=Many", True)] + [
        (f"Account {n}", f"otpauth://totp/Many:account{n}?secret={base64.b32encode(f'many-codes-{n:02}'.encode()).decode().rstrip('=')}&issuer=Many", True)
        for n in range(2, 13)]),
    ("No code yet (fictitious)", []),
    ("Steam (fictitious)", [("otp", "otpauth://totp/Steam:me?secret=GEZDGNBVGY3TQOJQ&period=30&digits=5&issuer=Steam&encoder=steam", True)]),
    ("Steam, legacy (fictitious)", [("TOTP Seed", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", True), ("TOTP Settings", "30;S")]),
    ("Counter-based (fictitious)", [("otp", "otpauth://hotp/Counter:me?secret=GEZDGNBVGY3TQOJQ&counter=3&issuer=Counter", True)]),
    ("Unknown algorithm (fictitious)", [("otp", "otpauth://totp/Algo:me?secret=GEZDGNBVGY3TQOJQ&algorithm=SHA3-256&issuer=Algo", True)]),
    # A field whose link gives no code: warned of in the editor, masked there and on the card (edit.one-time-code AC7).
    ("Broken link (fictitious)", [("GitHub 2FA", "otpauth://totp/GitHub:me?secret=0OIL&issuer=GitHub")]),
]

# An entry that never expired, its ExpiryTime its creation time long ago, as KeePassXC leaves it: Expires switched on
# in the editor starts a year ahead (edit.entry-fields).
OLD_TIMES = ("Never expired (fictitious)", "<Times><CreationTime>2019-04-01T10:00:00Z</CreationTime>"
             "<LastModificationTime>2019-04-01T10:00:00Z</LastModificationTime><LastAccessTime>2019-04-01T10:00:00Z</LastAccessTime>"
             "<ExpiryTime>2019-04-01T10:00:00Z</ExpiryTime><Expires>False</Expires><UsageCount>0</UsageCount>"
             "<LocationChanged>2019-04-01T10:00:00Z</LocationChanged></Times>")

# Entries with field types (the entry's CustomData, as the app writes them): a list of recovery codes beside an empty field.
TYPED_ENTRIES = [
    ("Recovery codes (fictitious)", [
        ("Recovery_1", "used 2026-10-22 · 1111-2222", True), ("Recovery_2", "3333-4444", True), ("Recovery_3", "", True),
        ("Recovery_4", "5555-6666", True), ("Recovery_5", "7777-8888", True), ("Backup e-mail", "")],
     {f"Recovery_{n}": "code" for n in range(1, 6)}),
]

# Entries with nothing but their title and a code (deleting the code moves the entry to the recycle bin).
BARE_ENTRIES = [
    ("Only a code (fictitious)", [("otp", "otpauth://totp/Bare:me?secret=ONSWG4TFORXXK3DE&issuer=Bare", True)]),
    ("Only a further code (fictitious)", [("Backup", "otpauth://totp/Bare:backup?secret=MZXW6YTBOI======&issuer=Bare", True)]),
]


def uuid():
    return base64.b64encode(os.urandom(16)).decode()


def field(key, value, protected=False):
    attribute = ' ProtectInMemory="True"' if protected else ""
    return f"<String><Key>{escape(key)}</Key><Value{attribute}>{escape(value)}</Value></String>"


def field_types(types):
    """As EntryFieldTypes.encode writes them: a JSON object, keys sorted."""
    return "{" + ",".join(f'"{k}":"{v}"' for k, v in sorted(types.items())) + "}"


def xml(recycle_bin=True):
    entries = "".join(
        f"<Entry><UUID>{uuid()}</UUID>{field('Title', title)}{field('UserName', 'me')}{field('Password', 'Otp-Pa55!', True)}"
        f"{''.join(field(*f) for f in fields)}</Entry>"
        for title, fields in ENTRIES
    ) + "".join(
        f"<Entry><UUID>{uuid()}</UUID>{field('Title', title)}{''.join(field(*f) for f in fields)}</Entry>"
        for title, fields in BARE_ENTRIES
    ) + "".join(
        f"<Entry><UUID>{uuid()}</UUID>{field('Title', title)}{field('UserName', 'me')}{field('Password', 'Otp-Pa55!', True)}"
        f"{''.join(field(*f) for f in fields)}<CustomData><Item><Key>Keething.FieldTypes</Key>"
        f"<Value>{escape(field_types(types))}</Value></Item></CustomData></Entry>"
        for title, fields, types in TYPED_ENTRIES
    ) + (f"<Entry><UUID>{uuid()}</UUID>{OLD_TIMES[1]}{field('Title', OLD_TIMES[0])}{field('UserName', 'me')}"
         f"{field('Password', 'Otp-Pa55!', True)}</Entry>")
    return ('<?xml version="1.0" encoding="utf-8" standalone="yes"?><KeePassFile><Meta><Generator>test</Generator>'
            f'<DatabaseName>One-time code formats</DatabaseName>'
            f'<RecycleBinEnabled>{"True" if recycle_bin else "False"}</RecycleBinEnabled></Meta><Root><Group><UUID>{uuid()}</UUID>'
            f'<Name>One-time code formats</Name><Group><UUID>{uuid()}</UUID><Name>Codes</Name>{entries}</Group></Group>'
            '</Root></KeePassFile>')


def main():
    args = [a for a in sys.argv[1:] if a != "--recycle-bin-off"]
    if len(args) != 1:
        sys.exit(__doc__)
    out = args[0]
    bin_off = "--recycle-bin-off" in sys.argv[1:]
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "otp.xml")
        imported = out if bin_off else os.path.join(tmp, "imported.kdbx")
        with open(source, "w") as f:
            f.write(xml(recycle_bin=not bin_off))
        subprocess.run([CLI, "import", "-q", "-p", source, imported], input=b"pw\npw\n", check=True)
        if bin_off:
            print(f"wrote {out}")
            return
        with open(FIXTURE, "rb") as src, open(out, "wb") as dst:
            dst.write(src.read())
        subprocess.run([CLI, "merge", "-q", "-s", out, imported], input=b"pw\n", check=True)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
