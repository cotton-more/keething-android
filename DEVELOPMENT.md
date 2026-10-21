# Developing Keething for Android

How to build, test and install the app on this Mac. What the app does is in SPEC.md, the plan in ROADMAP.md.

## What is needed

- JDK 21 (`java -version`), the Android command-line tools in `/opt/homebrew/share/android-commandlinetools`
  (`ANDROID_HOME`), KeePassXC in `/Applications` for the checks that use `keepassxc-cli`. Android Studio is
  optional; everything runs from the command line with the Gradle wrapper.
- Gradle needs `ANDROID_HOME`; the scripts in `tools/` set it themselves:

      export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

## Tests

    ./gradlew test                  # every module's JVM tests (core/* and app)
    ./gradlew :core:kdbx:test       # one module

## Building the APK

    tools/build-apk.sh              # release build, signed; asks for the keystore's password
    tools/build-apk.sh --install    # and installs it with adb on the connected phone

The APK lands in `~/Downloads` as `Keething-<version>-<commit>.apk` (shrunk with R8, about 4 MB). It is
signed with the keystore in `KEYSTORE` (default `~/.keystores/keething.jks`); the script asks for its
password, which never goes into a file or onto the command line. Uncommitted changes are built too (it says
so).

### The signing key (once)

    mkdir -p ~/.keystores && chmod 700 ~/.keystores
    keytool -genkeypair -keystore ~/.keystores/keething.jks -alias keething -keyalg RSA -keysize 4096 \
        -validity 36500 -dname "CN=Keething"

Keep its password in your password manager and a backup of the `.jks` file somewhere safe: an installed app
is only updated by an APK signed with the same key. Without the key, the only way is to uninstall, which
deletes the app's settings and its fingerprint setup.

Up to 2026-10-21 the test builds were signed with the Mac's debug key (`~/.android/debug.keystore`, password
`android`: whoever has that file can sign an update the phone accepts). The test phone still has such a build;
update it with `KEYSTORE=~/.android/debug.keystore tools/build-apk.sh`, or uninstall it before installing one
signed with the own key.

## Installing on the phone

Either way, an install over an older version signed with the same key keeps its settings.

- **Through Syncthing**, no cable: copy the APK into a folder that syncs to the phone (for example
  `cp ~/Downloads/Keething-*.apk ~/KeePass/`), open it on the phone in Files › that folder, allow Files to
  install apps when asked, then Install (or Update). Delete the APK from the folder afterwards.
- **With adb**: over a USB cable, or over Wi-Fi with Developer options › Wireless debugging (see below):
  `tools/build-apk.sh --install`, or `adb install -r <apk>`.

First start of a new install: allow "All files access", choose the Syncthing folder and the `.kdbx` file,
unlock with the password. Optional: the fingerprint (Settings in the app, or the switch under the password
field), and Keething as the autofill service (Settings › Passwords & autofill; in Chrome also Settings ›
Autofill services › "Autofill using another service").

## Wireless adb to the phone

On this Mac `adb` cannot reach the phone over Wi-Fi: it fails with "protocol fault" or "No route to host"
(macOS Local Network privacy blocks the Google-signed binary; the real error is in
`$TMPDIR/adb.$(id -u).log`). `tools/adb-wireless.py` goes around it with a small forwarder on 127.0.0.1:

    tools/adb-wireless.py <phone IP or .local name> --pair <port> <code>   # once: pairing dialog open
    tools/adb-wireless.py <phone IP or .local name>                        # every time after that

It finds the current connect port over Bonjour, connects adb to `127.0.0.1:15555` and keeps forwarding
while it runs (`ANDROID_SERIAL=127.0.0.1:15555`). The pairing port and code are in Developer options ›
Wireless debugging › "Pair device with pairing code"; the dialog must stay open while pairing.

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
