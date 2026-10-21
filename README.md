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

## Building the APK

    tools/build-apk.sh              # release build, signed; asks for the keystore's password
    tools/build-apk.sh --install    # and installs it with adb on the connected phone

The APK lands in `~/Downloads` as `KeePassBar-<version>-<commit>.apk`. It is signed with the keystore in
`KEYSTORE` (default `~/.keystores/keepassbar.jks`). Making that keystore, once (its password stays in your
head or your password manager, never in a file; keep a backup of the `.jks` file: without it no update of the
installed app is possible, only uninstalling, which deletes its settings and the fingerprint setup):

    mkdir -p ~/.keystores && chmod 700 ~/.keystores
    keytool -genkeypair -keystore ~/.keystores/keepassbar.jks -alias keepassbar -keyalg RSA -keysize 4096 \
        -validity 36500 -dname "CN=KeePassBar"

Copy the APK to the phone (a Syncthing folder, then open it in Files) or install it with `--install`.
Up to 2026-10-21 the test builds were signed with the Mac's debug key (`~/.android/debug.keystore`); an
install signed with it cannot be updated by one signed with the own key: uninstall it first.

## Trying the writing on a copy of a database

    tools/kdbx-probe.sh <copy.kdbx>     # asks for the password; changes the copy, prints no values

Reads and writes back the copy unchanged and compares KeePassXC's exports, then makes eight checked saves on it
(a new entry, an edit, the recycle bin and back), and compares it with the original (SPEC §3.3). Always on a
copy outside the Syncthing folder.

## Checking autofill on the phone

`tools/autofill-test/` holds local test pages (a login with and without hints, username first, a one-time
code, a sign-up, a frame of another site, and a login and a sign-up that are sent, for the save offer) and a script that writes a fictitious database whose entries
match them (password `pw`):

    tools/autofill-test/make-database.py autofill.kdbx        # needs KeePassXC's keepassxc-cli
    adb push autofill.kdbx /sdcard/Sync/KeePassBar-test/
    python3 -m http.server 8000 --bind 127.0.0.1 --directory tools/autofill-test/pages
    adb reverse tcp:8000 tcp:8000                             # the phone's localhost:8000 is the Mac's

Then open `http://localhost:8000` in Chrome (Settings › Autofill services › "Autofill using another
service") or Firefox on the phone.

The public suffix list is built in (`core/search`); `tools/update-public-suffix-list.sh` fetches a new one.
