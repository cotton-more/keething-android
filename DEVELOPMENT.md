# Developing Keething for Android

How to build, test and install the app on this Mac. What the app does is in SPEC.md, what comes next in `~/Projects/keething-spec` (`./status --gaps`, its ideas and `plans/`).

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

    tools/build-apk.sh              # release build, signed; the keystore's password from the Keychain, else asked
    tools/build-apk.sh --install    # and installs it with adb on the connected phone

The APK lands in `~/Downloads` as `Keething-<version>-<commit>.apk` (shrunk with R8, about 4 MB). It is
signed with the keystore in `KEYSTORE` (default `~/.keystores/keething.jks`). The default keystore's password
comes from the login Keychain item `keething-apk-keystore` when there is one (stored once with
`security add-generic-password -a "$USER" -s keething-apk-keystore -w`, which prompts for it; since 2026-10-29), so
the script, and Claude, can build without asking; anything running as the user can then sign with that key. Without
the item, or for another `KEYSTORE`, apksigner asks. The password reaches apksigner through the environment, never a
file or the command line. Uncommitted changes are built too (it says so).

### The signing key (once)

    mkdir -p ~/.keystores && chmod 700 ~/.keystores
    keytool -genkeypair -keystore ~/.keystores/keething.jks -alias keething -keyalg RSA -keysize 4096 \
        -validity 36500 -dname "CN=Keething"

Keep its password in your password manager and a backup of the `.jks` file somewhere safe: an installed app
is only updated by an APK signed with the same key. Without the key, the only way is to uninstall, which
deletes the app's settings and its fingerprint setup.

A good place for both is the password database itself: an entry "Keething signing key" with the key's
password as its password and `keething.jks` as an attachment (encrypted with the database, synced and backed
up with it). On another computer, save that attachment to `~/.keystores/keething.jks` (KeePassXC: the
attachment › Save; Keething on the Mac: the card › Save to a folder), make the folder private
(`mkdir -p ~/.keystores && chmod 700 ~/.keystores`) and build as usual; or point `KEYSTORE` at wherever it
was saved. Never put the `.jks` file into the project folder (it could end up in git), and delete it again
from a computer that is not yours.

Losing the key or its password does not touch the database (it lives in the Syncthing folder): the app is
uninstalled once, which deletes its own settings (the chosen database, the fingerprint setup, recent entries,
its backups), and a build signed with a new key installed. For Google Play this key can serve as the upload
key; Play signs the published app with a key of its own (keething-spec `app.public-release`).

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

First start of a new install (and the first start after the list of databases came in, plan AD4: the single path
of before is not carried over): the welcome's Open a database…, the `.kdbx` file in the system picker (from the
phone's storage, so that it has a path), "Allow all-files access" when the lock screen asks, then the password. Optional: the fingerprint (Settings in the app, or the switch under the password
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
(a new entry, an edit, the recycle bin and back), and compares it with the original (SPEC §3.3). The copy and
every file it writes are checked against KeePass's `fixtures/KDBX_XML.xsd` and by the app's own check; none may
add a violation. Always on a copy outside the Syncthing folder.

## The field types' former key renamed

    tools/rename-field-types.sh <database.kdbx> <new.kdbx>   # asks for the password; writes only the new file

A database from before 2026-10-21 keeps its entries' field types under the apps' former name, which this app no
longer reads. The tool renames that `CustomData` item in every entry, history item and the recycle bin into a new
file (no entry's times change, nothing goes into the history), then checks it: it opens here with as many entries,
and KeePassXC's export of it is the original's with only the key renamed. Then: the Mac app quit, the phone's
locked, the database file replaced with the new one (the original kept aside), Syncthing left to deliver it.
Prints counts, never a value.

## Checking one-time codes in other apps' formats

    tools/make-otp-database.py otp-formats.kdbx     # needs KeePassXC's keepassxc-cli; password pw

A fictitious database whose codes are in every format other apps keep them in (keething-spec `kdbx.otp-storage`):
KeePass 2.x's `TimeOtp-*` with the secret in each of its four encodings (one broken: no code, only its other field
shown), KeePassXC's `otp` link and the legacy `TOTP Seed`. Opened on the phone, each list row shows its code; compare
them with the codes KeePassXC shows (`keepassxc-cli show -t`). Most of its codes are KeePass's, so a code written into
it (an entry received, §10) takes KeePass's fields.

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

## Checking sharing between two devices

Sharing (SPEC §10) needs two Keething installs: the test phone and the emulator do, each with a fictitious
database. A request or an answer goes from one to the other as its picture: Save picture on one, then hand it to
"Read with Keething" on the other as a messenger would (`-s` picks the device):

    adb -s <from> pull /sdcard/Pictures/Keething/keething-request.png .
    adb -s <to> push keething-request.png /sdcard/Download/
    adb -s <to> shell content query --uri content://media/external/images/media \
        --projection _id --where "_display_name=\'keething-request.png\'"     # its media id
    adb -s <to> shell am start -a android.intent.action.SEND -t image/png \
        --eu android.intent.extra.STREAM content://media/external/images/media/<id> \
        -n dev.keething.android/.sharing.ReceiveCodeActivity

"Read with Keething" reads only pictures another app serves (`content:`), as a messenger does; a `file:` path
gets "No code in what was shared".

A code as text (an envelope's from Send as text, say) goes the same way, without a file:

    adb -s <to> shell am start -a android.intent.action.SEND -t text/plain \
        --es android.intent.extra.TEXT "'keething:…'" -n dev.keething.android/.sharing.ReceiveCodeActivity

An envelope's ten pictures are read from the sender's screen (`uiautomator dump`: the pictures' description lists
their names) and tapped on the other one.

An answer of several codes is saved as `keething-answer-1-of-2.png` and so on: hand them over one after the other.
`keepassxc-cli show -s --all` on the receiving database (pulled to the Mac) shows what was saved. The camera
needs a hand: point the phone at the other screen.
