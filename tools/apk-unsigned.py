#!/usr/bin/env python3
"""Writes a signed APK without its signature: the APK Signing Block (v2 and later) taken out from before the central
directory with the zeros apksigner pads the entries with so that the block starts on a 4 KiB page, and the central
directory's offset in the end record set back. A build signed after nothing but zipalign
(tools/build-apk.sh) then comes out as the unsigned build it was made from (keething-spec app.fdroid-build).
A v1 signature (META-INF) would be part of the archive's entries; tools/build-apk.sh signs without one.
Usage: tools/apk-unsigned.py <signed.apk> <out.apk>"""
import struct
import sys
from pathlib import Path

MAGIC = b"APK Sig Block 42"

data = Path(sys.argv[1]).read_bytes()
end = data.rfind(b"PK\x05\x06")  # the end of central directory record (the APK has no zip comment)
if end < 0:
    sys.exit("not a zip archive")
directory = struct.unpack_from("<I", data, end + 16)[0]
if data[directory - 16:directory] != MAGIC:
    sys.exit("no APK Signing Block before the central directory")
# The block's size, written at its start and again before the magic, counts all of it but the first size field.
size = struct.unpack_from("<Q", data, directory - 24)[0]
start = directory - size - 8
if struct.unpack_from("<Q", data, start)[0] != size:
    sys.exit("the APK Signing Block's two sizes differ")
entries = []  # (name, where its local header is) from the central directory
i = directory
while i < end:
    if data[i:i + 4] != b"PK\x01\x02":
        sys.exit("the central directory is not as expected")
    name_length, extra_length, comment_length = struct.unpack_from("<HHH", data, i + 28)
    entries.append((data[i + 46:i + 46 + name_length], struct.unpack_from("<I", data, i + 42)[0]))
    i += 46 + name_length + extra_length + comment_length
if any(name.startswith(b"META-INF/") and name.endswith((b".SF", b".RSA", b".EC", b".DSA")) for name, _ in entries):
    sys.exit("a v1 (JAR) signature is in the archive: not handled")


def entry_end(header):
    flags, = struct.unpack_from("<H", data, header + 6)
    if flags & 8:
        sys.exit("an entry with a data descriptor: not handled")
    size, = struct.unpack_from("<I", data, header + 18)
    name_length, extra_length = struct.unpack_from("<HH", data, header + 26)
    return header + 30 + name_length + extra_length + size


entries_end = max(entry_end(header) for _, header in entries)
if data[entries_end:start].strip(b"\0"):
    sys.exit("something other than padding between the last entry and the APK Signing Block")
start = entries_end
unsigned = bytearray(data[:start] + data[directory:])
struct.pack_into("<I", unsigned, end - (directory - start) + 16, start)
Path(sys.argv[2]).write_bytes(unsigned)
