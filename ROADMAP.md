# Roadmap

In this order; each step ends with an APK the owner installs on the phone. What the app does is in SPEC.md.

Each step starts as CLAUDE.md says: a short summary and the effort level it needs, then the owner's "ok".
The "Effort" line of a step is the suggested level (medium / high / xhigh / max).

## 1. Storage experiment (decides SPEC §3.1)

Effort: high.

- First, install the debug APK on the phone over wireless debugging (`adb pair` / `adb connect`) to
  prove the path (left over from the toolchain step, which was verified on the emulator).
- The `DatabaseStore` interface in `core/domain` first; the experiment is its all-files implementation.
- A debug screen: pick the Syncthing folder (all-files access), show the database's size / modification
  time, watch it with `FileObserver`. Change the database on the Mac, let Syncthing deliver it, and check
  the app sees the new file and never loses it. Same for a conflict copy.
- Write a test file the way §3.3 step 7 says (ignored temporary name, rename) and check Syncthing sends
  only the final file to the Mac.

## Design

Effort: high.

Clickable mock-ups (an artifact) of the lock screen, list, card, editor, the password menu (QR, large type,
share) and the code notification, light and dark, following SPEC §6.1. Iterate with the owner until they
are liked; the Compose screens are built from them.

## 2. Read-only vault

Effort: xhigh (KDBX reader, Argon2, protected values); high for the app screens.

- core: KDBX 4 reader (port of the Mac `KDBXReader`: header, AES-KDF / Argon2, AES-256 / ChaCha20, HMAC
  blocks, gzip, inner header, protected values), XML → Entry (Mac `Parsing`), TOTP, search. JVM tests on
  the fixtures and the Mac tests' expectations.
- Argon2: measure on the phone (64 MiB, 42 iterations on the real database). A native library
  (e.g. argon2kt) if pure Kotlin is too slow.
- app: lock screen, list with search, card, copy with a sensitive clipboard, live one-time codes, auto-lock,
  `FLAG_SECURE`, reload on file change.

## 3. Fingerprint unlock (SPEC §5)

Effort: xhigh (Keystore, biometrics).

## 4. Writing

Effort: max (XML tree, writer, the save checks; a mistake can damage the database).

- core: XML tree that keeps everything, writer, EntryEditing / EntryLifecycle / AttachmentPool, the save
  checks (port of `KDBXFileSaver`). JVM tests: written files read back identical; keepassxc-cli on the Mac
  opens them (the tests can call it when it is installed).
- app: editor, new entry, delete with Undo, recycle bin (`-`), restore, backups, replace by rename.
- Check on a copy of the real database (as the Mac's `KDBXProbe --lifecycle`), then with Syncthing for real.

## 5. Attachments

Effort: high.

Open in another app (FileProvider), save to a chosen place.

## 6. Autofill (SPEC §7)

Effort: high.

Fill in apps, then in Chrome and Firefox; locked → unlock → fill; inline suggestions; one-time codes; save
and update offers; the password generator for sign-ups.

## 7. Quick access and sharing

Effort: high.

- Pin a one-time code to the notification shade (SPEC §6.3).
- The "Codes" launcher shortcut.
- Password menu: Show as QR, Large type, Share… with the warning.

## Later

Effort: per item, said when it starts.

Setting up one-time codes (secret, link, QR from the camera), merging a Syncthing conflict copy, history,
adding / removing / renaming attachments, reordering custom fields.

## Last, maybe: Google Play (SPEC §8.1)

Effort: xhigh (SAF storage replaces the file path; a security review before release).

The SAF `DatabaseStore` implementation, privacy policy, store listing, a donation option.
