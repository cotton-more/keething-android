# KeePassBar for Android

An Android client for a KeePassXC database synchronised with Syncthing: fingerprint unlock, search,
readable entry cards, one-time codes, editing, attachments and autofill. The companion of the macOS app
KeePassBar. Not on Google Play: built and installed as a self-signed APK.

See SPEC.md for the behaviour and ROADMAP.md for the plan.

## Wireless adb to the phone

On this Mac `adb` cannot reach the phone over Wi-Fi: it fails with "protocol fault" or "No route to host"
(macOS Local Network privacy blocks the Google-signed binary; the real error is in
`$TMPDIR/adb.$(id -u).log`). `tools/adb-wireless.py` goes around it with a small forwarder on 127.0.0.1:

    tools/adb-wireless.py <phone IP or .local name> --pair <port> <code>   # once: pairing dialog open
    tools/adb-wireless.py <phone IP or .local name>                        # every time after that

It finds the current connect port over Bonjour, connects adb to `127.0.0.1:15555` and keeps forwarding
while it runs (`ANDROID_SERIAL=127.0.0.1:15555`). The pairing port and code are in Developer options ›
Wireless debugging › "Pair device with pairing code"; the dialog must stay open while pairing.

## Checking autofill on the phone

`tools/autofill-test/` holds local test pages (a login with and without hints, username first, a one-time
code, a sign-up, a frame of another site) and a script that writes a fictitious database whose entries
match them (password `pw`):

    tools/autofill-test/make-database.py autofill.kdbx        # needs KeePassXC's keepassxc-cli
    adb push autofill.kdbx /sdcard/Sync/KeePassBar-test/
    python3 -m http.server 8000 --bind 127.0.0.1 --directory tools/autofill-test/pages
    adb reverse tcp:8000 tcp:8000                             # the phone's localhost:8000 is the Mac's

Then open `http://localhost:8000` in Chrome (Settings › Autofill services › "Autofill using another
service") or Firefox on the phone.

The public suffix list is built in (`core/search`); `tools/update-public-suffix-list.sh` fetches a new one.
