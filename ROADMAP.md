# Roadmap

In this order; each step ends with an APK the owner installs on the phone. What the app does is in SPEC.md.
The agreed screen mock-ups (SPEC §6) are the canvas https://claude.ai/artifact/VvguNamsGWxY4zQwsHUaEa;
the Compose screens are built from them.

Each step starts as CLAUDE.md says: a short summary and the effort level it needs, then the owner's "ok".
The "Effort" line of a step is the suggested level (medium / high / xhigh / max).

## 1. Storage experiment (decides SPEC §3.1)

Effort: high.

Done on the emulator: the `DatabaseStore` port, the all-files `PathDatabaseStore`, the storage probe screen,
and the experiment with Syncthing-Fork (SPEC §3.1–3.3 record what it found). Left, on the phone:

- Install the debug APK over wireless debugging (`adb pair` / `adb connect`) to prove the path.
- Run the probe against a test folder shared with the Mac (not the real database): a change from the Mac,
  a conflict copy, "Write test file". Confirm the phone behaves as the emulator did.

## 2. Read-only vault

Effort: high.

Done: the core (KDBX 4 reader, XML → vault, one-time codes, search, card layout, session), and the app
screens, checked on the Android 17 emulator with a fictitious database of 1,900 entries: choosing the
database, lock screen, list with search, card, sensitive clipboard cleared after 30 s, live codes, auto-lock
(with an alarm, as Android freezes the app in the background), `FLAG_SECURE`, reloads and conflict copies
on screen, light and dark. Left, on the phone:

- Unlock the real database: how long the key derivation takes (64 MiB, 42 iterations; the list says it
  after the unlock) and the whole unlock; the memory (Settings › This phone; `android:largeHeap` is on).
  On the loaded emulator the first unlock in a process took 4 s for a 165 KB file in a release build
  (0.3 s once warm, 50 ms on the Mac's JVM): cold code. If the phone is slow too, a baseline profile; a
  native Argon2 (e.g. argon2kt) only if the key derivation itself is the slow part.
- The auto-lock and the clipboard clearing with the screen off and the phone in Doze.

## 3. Fingerprint unlock (SPEC §5)

Effort: xhigh (Keystore, biometrics).

## 4. Attachments

Effort: high.

Open in another app (FileProvider), save to a chosen place.

## 5. Autofill, filling only (SPEC §7)

Effort: high.

- core: site matching (port of the Mac `SiteMatcher`, `SiteAddress` and the public suffix list).
- Fill in apps, then in Chrome and Firefox; locked → unlock → fill; inline suggestions; one-time codes.
The save and update offers come with writing (step 7).

## 6. Quick access and sharing

Effort: high.

- Pin a one-time code to the notification shade (SPEC §6.3).
- The "Codes" launcher shortcut.
- Password menu: Show as QR, Large type, Share… with the warning.
- The Wi-Fi network's QR code on the card (Mac SPEC §3.13; the card shows the network's fields until then).

## 7. Writing (blocks a Google Play release)

Effort: max (XML tree, writer, the save checks; a mistake can damage the database).

The app is read-only until this step: it cannot damage the database. Editing on the phone is rare for
the owner, so writing comes after autofill and before Google Play, without hurry.

- core: XML tree that keeps everything, writer, EntryEditing / EntryLifecycle / AttachmentPool, the save
  checks (port of `KDBXFileSaver`). JVM tests: written files read back identical; keepassxc-cli on the Mac
  opens them (the tests can call it when it is installed).
- app: editor, new entry, delete with Undo, recycle bin (`-`), restore, backups, replace by rename.
- Autofill: the save and update offers, the password generator for sign-ups.
- Check on a copy of the real database (as the Mac's `KDBXProbe --lifecycle`), then with Syncthing for real.

## Later

Effort: per item, said when it starts.

Setting up one-time codes (secret, link, QR from the camera), merging a Syncthing conflict copy, history,
adding / removing / renaming attachments, reordering custom fields.

## Last, maybe: Google Play (SPEC §8.1)

Effort: xhigh (SAF storage replaces the file path; a security review before release).

The SAF `DatabaseStore` implementation, privacy policy, store listing, a donation option.
