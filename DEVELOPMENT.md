# Developing Keething for Android

How to build, test and install the app on this Mac. What the app does is in SPEC.md, what comes next in `~/Projects/keething-spec` (`./status --gaps`, its ideas and `plans/`).

## What is needed

- JDK 21 (`java -version`), the Android command-line tools in `/opt/homebrew/share/android-commandlinetools`
  (`ANDROID_HOME`), KeePassXC in `/Applications` for the checks that use `keepassxc-cli`. Android Studio is
  optional; everything runs from the command line with the Gradle wrapper.
- The NDK and CMake that build the native Argon2 (`core/argon2`), at the versions pinned in
  `gradle/libs.versions.toml` (`ndk`, `cmake`); once:

      sdkmanager "ndk;30.0.16248370" "cmake;4.1.2"
- Gradle needs `ANDROID_HOME`; the scripts in `tools/` set it themselves:

      export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

The commands below are also in `Taskfile.yml` (go-task: `brew install go-task`): `task` lists them, `task <name>`
runs one, arguments after `--` (`task test:module -- kdbx`).

## Tests

    ./gradlew test                  # every module's JVM tests (core/* and app)
    ./gradlew :core:kdbx:test       # one module

The native Argon2's tests run on a phone (`core/argon2/src/androidTest`, keething-spec unlock.faster-argon2): `task
test:device` (`ANDROID_SERIAL=<serial>` for one device) installs their APK, runs it with `am instrument` and removes
it. Not Gradle's `connectedDebugAndroidTest`: AGP 9.4.1 fails it with every test passed. `task spec:verify` does not
run them. One of them leaves a file created with the native Argon2 for KeePassXC; to check it, run that test alone
with the APK kept (`./gradlew :core:argon2:installDebugAndroidTest`, then `adb shell am instrument -w -e class
dev.keething.argon2.NativeArgon2Test#aFileCreatedWithItOpensWithTheKotlinOne
dev.keething.argon2.test/androidx.test.runner.AndroidJUnitRunner`), `adb pull` the path its log line names, `keepassxc-cli
db-info` it (password `pw`), then `adb uninstall dev.keething.argon2.test`.

## Texts the user sees

Every text the app shows is a string resource, in `app/src/main/res/values/strings_<package>.xml` (the app's own in
`strings.xml`): a count is a `<plurals>`, a name goes in by a placeholder, a sentence is never joined from pieces. A
composable reads it with `stringResource`; a view model or a plain function returns a `UiText`
(`app/src/main/kotlin/dev/keething/android/ui/UiText.kt`), shown with `resolve()`. A unit test reads a `UiText` in
English with `english()` (`app/src/test/kotlin/dev/keething/android/ui/EnglishTexts.kt`), so the expected texts stay
the words on the screen.

    task texts:check            # texts still written into app/'s code (tools/check-texts.py); must print nothing

A literal that is not a text the user sees (a key, a tag, a MIME type) carries `// not user text` on its line; a whole
file is left out in `tools/texts-allowed.txt`, with why. The core modules' texts are still English Strings (keething-spec
plans/android-release-1.0.md T13b).

## Building the APK

    tools/build-apk.sh              # release build, signed; the keystore's password from the Keychain, else asked
    tools/build-apk.sh --install    # and installs it with adb on the connected phone
    tools/build-apk.sh ~/KeePass    # into that folder instead (task apk -- ~/KeePass)

The APK lands as `Keething-<version>-<commit>.apk` (shrunk with R8, about 4 MB) in the folder given, else in
`KEETHING_APK_DIR` when it is set (in `~/.zshrc`, say `export KEETHING_APK_DIR=~/KeePass`: a folder that syncs to
the phone, so every `task apk` reaches it), else in `~/Downloads`. It is
signed with the keystore in `KEYSTORE` (default `~/.keystores/keething.jks`). The default keystore's password
comes from the login Keychain item `keething-apk-keystore` when there is one (stored once with
`security add-generic-password -a "$USER" -s keething-apk-keystore -w`, which prompts for it; since 2026-10-29), so
the script, and Claude, can build without asking; anything running as the user can then sign with that key. Without
the item, or for another `KEYSTORE`, apksigner asks. The password reaches apksigner through the environment, never a
file or the command line. Uncommitted changes are built too (it says so).

### Reproducing a release (F-Droid)

    task apk:reproduce -- ~/KeePass/Keething-1.0.0-<commit>.apk

The build is reproducible (keething-spec app.fdroid-build): F-Droid builds the commit itself, compares its APK with
ours and ships ours. This makes the same comparison here before a release: a fresh clone of the APK's commit (read
from its About) built in a folder with a space, and the APK without its signing block (`tools/apk-unsigned.py`) found
the same byte for byte. Only an APK built from a commit with nothing uncommitted can be reproduced; its About's time
is then the commit's. The recipe F-Droid builds from is `tools/fdroid/dev.keething.android.yml`.

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

- **Through Syncthing**, no cable: build the APK into a folder that syncs to the phone (`task apk -- ~/KeePass`,
  or `KEETHING_APK_DIR` set to it), open it on the phone in Files › that folder, allow Files to
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

With several devices connected (the phone through the forwarder and by its own wireless connection, the emulator),
`task install:debug` installs and starts on each of them; `ANDROID_SERIAL=127.0.0.1:15555 task install:debug` (or any
task) keeps to one, since adb and Gradle both read it.

## Trying the writing on a copy of a database

    tools/kdbx-probe.sh <copy.kdbx>     # asks for the password; changes the copy, prints no values

Reads and writes back the copy unchanged and compares KeePassXC's exports, then makes eight checked saves on it
(a new entry, an edit, the recycle bin and back), and compares it with the original (SPEC §3.3). The copy and
every file it writes are checked against KeePass's `fixtures/KDBX_XML.xsd` and by the app's own check; none may
add a violation. Always on a copy outside the Syncthing folder.

## Measuring a large database

keething-spec app.large-database (plans/android-large-database.md there): how fast and in how much memory a large
database unlocks, searches, opens a card and saves.

    task large:database -- large-5000.kdbx                     # 5,000 entries, 40 MiB of attachments, Argon2 64 MiB × 2
    task large:database -- large-10000.kdbx 10000 80           # 82.7 MiB
    task large:save -- 512m <copy.kdbx> [beside.kdbx or -] [saves]   # on the Mac: unlock and saves within a JVM heap

The generator's content is the same each time (a fixed seed), password `pw`. `task large:save` changes the copy; it
prints the times and fails with `OutOfMemory` when the heap given is too small (the least heap that passes is what a
save needs on a JVM; the phone needs somewhat more). `memoryBudgetTest` in core:vault, run by `task test`, keeps a
40 MiB database's unlock and saves within 224 MiB (it needs about 200).

### Before each release (app.large-database AC6)

AC1–AC3 measured on the Pixel 6 with the release build's code; the numbers go into the release's checks
(keething-spec plans/android-release-1.0.md T11 for 1.0). A budget missed blocks the release, or is written there with
the owner's word. About half an hour.

    task large:files                 # the four files made into build/large (a fixed seed) and pushed to Documents/Large
    task apk:timings                 # the release build keeping Log.i's timing lines, installed
    task large:measure               # the timing run: drive the scenarios below, Lock all between them, Ctrl-C
    task large:measure -- --heap     # the heap run: scenarios 1-3 again, with the Java heap sampled
    task apk:install                 # the release build back

`large:files` makes only the files not in build/large yet (`-- --fresh` makes them again) and replaces the phone's
copies, so lock the app first; add each file once in the app (Open other… › Documents/Large), password `pw`. The
scenarios, each from the lock screen (check only the databases named, in this order):

1. large-5000 alone: type a search of eight characters and back, open a few cards, save three changes (a card's
   ⋮ › Move to the recycle bin is one). AC1's budgets: listed within 3 s of Unlock, a keystroke within 100 ms, a card
   within 300 ms, a save within 4 s.
2. large-90 (89.8 MiB) alone: near the limit, OK; save two changes (AC2).
3. large-5000 then large-80 (79.5 MiB): both changeable, large-80 near the limit; save a change in each (AC2).
4. large-100 (100.1 MiB) alone: read-only, said; a change tried is stopped (AC3).
5. large-5000 then large-90: large-90 read-only because of large-5000 (AC3).

Ctrl-C ends a run, keeps its log in build/large (`task large:summary -- <log>` prints it again) and prints each
scenario: its databases (the log names them by an id; the summary by their size, the build/large file of that size),
how AC3 judged each (`room`: Enough, Near, TooLarge(alone=true/false); an OK on the near-the-limit banner logs
Enough), unlock, listing, searches, cards, saves, and the heap at each save's peak in the heap run. It judges AC1's
budgets where large-5000 is alone and fails a save that failed anywhere; its exit status is 1 then. The heap is
`dumpsys meminfo`'s Dalvik Heap *Heap Alloc* of the 512 MiB, sampled about every 0.3 s: each sample makes the app
collect its garbage, so the heap run's times are not judged. AC2 holds when every save in 2 and 3 is done; the
peaks against the earlier ones (plan §1) say whether the room is shrinking.

The screens are captured black (`FLAG_SECURE`): drive them by hand, or through `adb shell uiautomator dump` and taps.
The timing lines, all removed from a release build with every `Log.i`: `Session` "<id> unlocking", "<id> unlocked: N
entries, key derived in … ms, … MiB, room …" and "<id> save done/failed in … ms", `List` "listing prepared in …" and
"search of N characters in … ms", `Card` "built in … ms". A measurement build (`-Pkeething.timings`) is never shipped:
`tools/build-apk.sh` builds without it unless `--timings`, which only installs.

## Stopping a save in the middle on the phone

    adb shell run-as dev.keething.android sh -c 'echo "rename .syncthing" > files/debug-pause'
    adb shell run-as dev.keething.android kill -9 $(adb shell pidof dev.keething.android)   # once the log says paused

Debug builds only (`PausingFolder`): the next call of that kind on a granted folder whose path holds the text waits
two minutes, and the log (tag `KeethingPause`) says when, so the process can be killed between a save's steps
(keething-spec databases.saf AC4, AC5) or while New database writes (AC11). The calls: `write` (the new file made,
still empty; `write S5Fam.kdbx` for a Create), `rename <the database's path>` (before it goes aside), `rename .syncthing`
(before the new file goes in), `delete .syncthing` (before the old file goes). The marker is removed when it pauses.
`am kill` does not end an app in front; `kill -9` through `run-as` does. `read <the database's name>` pauses the
next read of the database instead: set it, then save a change, and lock while the save waits at its first step
(keething-spec kdbx.hardening AC3: after 10 s it is stopped, and the next unlock says the change was not saved).

## Checking a screen for a screen reader

    task a11y:dump                       # the screen shown: unnamed controls, a secret in the tree
    task a11y:dump -- --tree             # and every node of the app as a reader meets it
    task a11y:dump -- --secret 4111111111111111   # more values that must never be in the tree (default: pw)

The screens are `FLAG_SECURE` (a screenshot is black), but uiautomator still gets the accessibility tree
(keething-spec app.accessibility AC1, AC2). It flags a node that acts (clickable, long-clickable, checkable) with no
text or description of its own or inside it, a node whose text or description is one of the secrets, and a password
field whose text is not dots; it exits 1 when anything is flagged. Headings, live regions and the reading order are not
in uiautomator's dump: those are TalkBack's to hear. With two devices connected (the forwarder and the phone's own
entry are the same Pixel), set `ANDROID_SERIAL`.

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

## Autofill's browsers

A browser's web domain is trusted only when its package is listed in `Browsers` (core/autofill) and the system
says it is signed with one of the certificates listed beside it (SPEC §7, Trust). Each certificate is the
SHA-256 of the signing certificate, in lower-case hex, with where it came from in the comment above it. To add a
browser or a certificate:

- Google's list of browsers privileged for passkeys gives most of them:
  `https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json`, each package's `cert_fingerprint_sha256`.
- An F-Droid repository's `index-v2.json` gives each version's `manifest.signer.sha256` (F-Droid's own key for
  a build F-Droid signs, the developer's for a reproducible one).
- Otherwise the release's APK: `apksigner verify --print-certs <apk>`, the "certificate SHA-256 digest".

A browser whose certificate cannot be found is left out (it is then an app: its web domain is ignored). Left out
2026-11-13: Mull (`us.spotco.fennec_dos`, discontinued, in no repository any more) and Ecosia
(`com.ecosia.android`, on Google Play only).

To check one that is not trusted: an app with a WebView showing `http://localhost:8000/login.html`, its package a
listed browser's that the phone does not have, signed with the debug key, is offered no entry of the page.

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

## The listing's screenshots

The listing (`fastlane/metadata/android/en-US`: its texts, eight framed screenshots, the feature graphic and the icon)
is made from fictitious databases on the test phone, debug build (a release build's `FLAG_SECURE` blanks every
screenshot), and framed on the Mac. For each release that changes what they show:

    task screens:database -- showcase            # Personal.kdbx and Family.kdbx, password pw (needs keepassxc-cli)
    adb shell mkdir -p /sdcard/Documents/Showcase && adb push showcase/*.kdbx /sdcard/Documents/Showcase/
    adb shell run-as dev.keething.android mkdir -p files
    adb shell run-as dev.keething.android touch files/allow-screenshots   # the debug build then allows them
    task screens:demo                             # 9:41, full battery; task screens:demo-off afterwards

The app's colours follow the phone's palette: the icon's blue is set without changing the wallpaper by

    adb shell "settings put secure theme_customization_overlay_packages '{\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.system_palette\":\"2F63E0\",\"android.theme.customization.accent_color\":\"2F63E0\",\"android.theme.customization.theme_style\":\"TONAL_SPOT\"}'"

Open the folder in the app (Open a database…, Documents/Showcase), both databases, and take each screenshot with
`adb exec-out screencap -p > <folder>/<name>.png` under the names in `tools/screens/frame.py` (`SHOTS`):

1. `1-list.png`: Personal's list, light, from the top.
2. `2-card.png`: Alder Bank's card, the password shown.
3. `3-card.png`: Alder Bank Visa.
4. `4-wallet.png`: Bitcoin cold wallet, the phrase shown.
5. `5-autofill.png`: Keething as the autofill service, `python3 -m http.server 8000 --bind 127.0.0.1 --directory
   tools/screens/pages` and `adb reverse tcp:8000 tcp:8000`, `http://localhost:8000` in Firefox, the Email field
   tapped: the keyboard offers Fjordmail (the entry's `KP2A_URL`). The frame leaves Firefox's address bar out.
6. `6-codes.png`: the Codes view, early in a 30 s period, so that the codes are not fading.
7. `7-share.png`: Sharing › Ask for an entry, a name and a reason, Create the request.
8. `8-sync.png`: dark theme (`adb shell cmd uimode night yes`), both databases open, a copy of Personal.kdbx with
   an entry added by keepassxc-cli pushed beside it as `Personal.sync-conflict-<date>-<time>-7ZKQ3RA.kdbx`: the list
   from the top, its banner.

Then `task screens:frame -- <folder>` draws them into the listing (Google Chrome headless, 1080 × 1920: Google Play
takes nothing longer than twice its width) with the feature graphic and the icon, from the launcher's own layers.
