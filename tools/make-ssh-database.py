#!/usr/bin/env python3
"""Writes a fictitious KDBX 4 database (password `pw`) of SSH logins with KeePassXC's agent settings and their key
attachments, for checking each line of the SSH face's key on a phone (keething-spec ssh.key-details):

    tools/make-ssh-database.py <output.kdbx>

Throwaway ed25519 keys made by ssh-keygen for this file: a key named by its settings (UTF-16 with a byte order mark,
as KeePassXC writes them), one under the passphrase `secret`, settings naming a file on the computer, a PKCS#8 key
(another format), an OpenSSH key with its cipher name's length broken (its first byte 0x80) and one cut short (both
unreadable), unreadable settings beside `a.key` and `b.key` (no key found), and a key-only entry (settings and key,
no host: nothing of its key shown, keething-spec ssh.key-alone). Each login's host is an unprotected field typed
`ssh.host`. The public lines of the readable keys are printed, to compare with the card's copy.

keepassxc-cli writes KDBX 3.1 on import, which holds no field types, so the import is merged into a copy of a KDBX 4
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


def uuid():
    return base64.b64encode(os.urandom(16)).decode()


def field(key, value, protected=False):
    attribute = ' ProtectInMemory="True"' if protected else ""
    return f"<String><Key>{escape(key)}</Key><Value{attribute}>{escape(value)}</Value></String>"


def settings(kind, name):
    """KeePassXC's KeeAgent.settings: UTF-16LE with a byte order mark."""
    text = ('<?xml version="1.0" encoding="UTF-16"?><EntrySettings><AllowUseOfSshKey>true</AllowUseOfSshKey>'
            '<AddAtDatabaseOpen>true</AddAtDatabaseOpen><RemoveAtDatabaseClose>true</RemoveAtDatabaseClose>'
            f'<Location><SelectedType>{kind}</SelectedType><AttachmentName>{escape(name if kind == "attachment" else "")}'
            f'</AttachmentName><FileName>{escape(name if kind == "file" else "")}</FileName></Location></EntrySettings>')
    return b"\xff\xfe" + text.encode("utf-16-le")


def keygen(tmp, name, passphrase=""):
    path = os.path.join(tmp, name)
    subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", passphrase, "-C", "test", "-f", path], check=True)
    with open(path, "rb") as f:
        return f.read()


def public_line(tmp, name):
    with open(os.path.join(tmp, name + ".pub")) as f:
        return " ".join(f.read().split()[:2])


def broken(key):
    """The key with the first byte of its cipher name's length set to 0x80."""
    lines = key.decode().strip().split("\n")
    body = bytearray(base64.b64decode("".join(lines[1:-1])))
    body[15] = 0x80
    text = base64.b64encode(bytes(body)).decode()
    return ("\n".join([lines[0]] + [text[i:i + 70] for i in range(0, len(text), 70)] + [lines[-1]]) + "\n").encode()


def entries(tmp):
    work, protected, cut = keygen(tmp, "id_work"), keygen(tmp, "id_protected", "secret"), keygen(tmp, "id_cut")
    lonely = keygen(tmp, "id_alone")
    pkcs8 = subprocess.run(["openssl", "genpkey", "-algorithm", "ed25519"], capture_output=True, check=True).stdout
    cut_short = b"\n".join(cut.split(b"\n")[:2]) + b"\n-----END OPENSSH PRIVATE KEY-----\n"
    print(f"Key with settings (id_work):     {public_line(tmp, 'id_work')}")
    print(f"Key under a passphrase:          {public_line(tmp, 'id_protected')}")
    host = "db.example.com:2222"
    return [
        ("Key with settings", host, {"KeeAgent.settings": settings("attachment", "id_work"), "id_work": work,
                                     "id_work.pub": open(os.path.join(tmp, "id_work.pub"), "rb").read()}),
        ("Key under a passphrase", host, {"KeeAgent.settings": settings("attachment", "id_ed25519"), "id_ed25519": protected}),
        ("Key in a file", host, {"KeeAgent.settings": settings("file", "~/.ssh/id_ed25519_db")}),
        ("PKCS#8 key", host, {"KeeAgent.settings": settings("attachment", "id_pkcs8"), "id_pkcs8": pkcs8}),
        ("Broken length", host, {"KeeAgent.settings": settings("attachment", "id_broken"), "id_broken": broken(work)}),
        ("Cut short", host, {"KeeAgent.settings": settings("attachment", "id_cut"), "id_cut": cut_short}),
        ("Which key", host, {"KeeAgent.settings": b"not settings", "a.key": work, "b.key": lonely}),
        ("Missing key", host, {"KeeAgent.settings": settings("attachment", "id_gone")}),
        ("Key only", None, {"KeeAgent.settings": settings("attachment", "id_alone"), "id_alone": lonely}),
    ]


def xml(tmp):
    binaries, body = [], ""
    for title, host, attachments in entries(tmp):
        refs = ""
        for name, data in attachments.items():
            refs += f'<Binary><Key>{escape(name)}</Key><Value Ref="{len(binaries)}"/></Binary>'
            binaries.append(data)
        fields = field("Title", title) + field("UserName", "alex") + field("Password", "Ssh-Pa55!", True)
        custom = ""
        if host:
            fields += field("Host", host)
            custom = '<CustomData><Item><Key>Keething.FieldTypes</Key><Value>{"Host":"ssh.host"}</Value></Item></CustomData>'
        body += f"<Entry><UUID>{uuid()}</UUID>{fields}{refs}{custom}</Entry>"
    meta = "".join(f'<Binary ID="{i}" Compressed="False">{base64.b64encode(d).decode()}</Binary>' for i, d in enumerate(binaries))
    return ('<?xml version="1.0" encoding="utf-8" standalone="yes"?><KeePassFile><Meta><Generator>test</Generator>'
            f'<DatabaseName>SSH keys</DatabaseName><Binaries>{meta}</Binaries></Meta><Root><Group><UUID>{uuid()}</UUID>'
            f'<Name>SSH keys</Name><Group><UUID>{uuid()}</UUID><Name>Servers</Name>{body}</Group></Group></Root></KeePassFile>')


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "ssh.xml")
        imported = os.path.join(tmp, "imported.kdbx")
        with open(source, "w") as f:
            f.write(xml(tmp))
        subprocess.run([CLI, "import", "-q", "-p", source, imported], input=b"pw\npw\n", check=True)
        with open(FIXTURE, "rb") as src, open(out, "wb") as dst:
            dst.write(src.read())
        subprocess.run([CLI, "merge", "-q", "-s", out, imported], input=b"pw\n", check=True)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
