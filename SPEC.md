# KeePassBar for Android — spec

A small Android client for the same KeePassXC database the Mac app KeePassBar (`~/Projects/keepass-bar`)
reads and writes: unlock with a fingerprint, search, view an entry with its custom fields laid out readably,
copy fields and one-time codes, edit and create entries, attachments, and autofill in apps and browsers —
with the system's "save this password?" offer after a login or sign-up.

Owner's context: one user, one database (`1Password.kdbx`, ~35 MB, ~1900 entries in ~14 groups, imported from
1Password, many custom fields, some one-time codes and attachments), password only (no key file, no
YubiKey), **synchronised with Syncthing** between the Mac and the phone. Phone: **Pixel 11 Pro, Android 17**.
Installed as a self-signed APK. Google Play is a possible last step, not a v1 goal (§8.1), but nothing
may block it: the storage choice in §3.1 is the one place where it matters.

Why not KeePassDX / Keepass2Android: KeePassDX keeps losing the database file when Syncthing delivers a
new version; Keepass2Android works but its interface is not liked. So the two things this app must do
better are: **never lose the file** (§3) and **a clean, readable, beautiful interface** (§6).

Not a kitchen sink: search, view, copy, edit, save, autofill, share a password — and nothing that does not
serve those.

## 1. Goals / non-goals

Goals (v1)
- Open the database from the Syncthing folder; notice new versions; never lose track of the file.
- Unlock with the master password, then with the fingerprint (§5).
- Search (as the Mac app: terms, fuzzy, `@group`, `#tag`, `+attachment`, `-` for the recycle bin).
- Entry card: title, group path, username, password (masked), URLs, one-time codes live with a
  countdown, notes, custom fields in file order with the Mac app's layout rules (sections, lists of codes,
  dates, links), attachments.
- Copy any field; the clipboard is marked sensitive and cleared after 30 s.
- Edit, create, recycle and restore entries (delete is undoable from a snackbar; emptying the recycle bin
  is left to the Mac); custom fields; attachments: open and save. One-time codes: show and copy (setting
  them up is optional, later).
- Share a password (§6.2): as a QR code on the screen, in large type, or through the share sheet after a
  warning.
- Autofill (§7): fill logins in apps and browsers, fill one-time codes, offer to save a new login or update a
  changed password, with a simple password generator for sign-ups.
- Quick access to one-time codes: a code pinned to the notification shade (§6.3) and a "Codes" launcher
  shortcut.

Non-goals (v1): SSH agent, a password generator beyond the one in the autofill save, e-mail aliases,
reports, history UI, key file / YubiKey, KDBX 3, Twofish, merging conflicting copies (detected and shown
only, §3.4), deleting entries for good / emptying the recycle bin, adding / removing / renaming
attachments, reordering custom fields, a Quick Settings tile, Google Play (§8.1).

## 2. Architecture

Kotlin, Jetpack Compose, Gradle (Kotlin DSL) with the wrapper checked in. Two modules to start:

```
core/   pure Kotlin (JVM library, no Android imports): domain (Entry, Vault, CustomField, Attachment,
        TOTPConfig, EntryChanges, VaultEdit), TOTP, search, KDBX 4 reader / writer / XML tree, entry
        editing and lifecycle, the save checks, the DatabaseStore interface (§3.1). Unit-tested on the
        JVM with the fixtures.
app/    Android: storage implementations (§3.1), session (lock, auto-lock), biometrics (§5), clipboard,
        Compose UI, AutofillService (§7), the code notification (§6.3). Depends on core, never the reverse.
```

Rules carried over from the Mac app:
- Dependencies point inward; anything with side effects (files, clock, clipboard, keystore, biometrics)
  is behind an interface the core owns, with fakes in tests.
- TDD: new behaviour starts with a failing test; tests are never loosened to make code pass.
- Fail closed on secrets: search, logs and autofill never see protected values except where a value is the
  thing being copied or filled.
- No `Log` of entry values or the master password, ever.

The Mac app's code (Swift) is the reference for every format detail: read it before writing the Kotlin
version. The key places:
- `Sources/KeePassBarKDBX/` — KDBXReader, KDBXWriter, XMLTree, EntryEditing, EntryLifecycle,
  AttachmentPool, Argon2, ChaCha20, BLAKE2b
- `Sources/KeePassBarLoading/KDBXFileSaver.swift` — re-read, apply, write, read back and verify
- `Sources/KeePassBarParsing/` — XML → Entry (which keys are custom fields, TOTP, attachments, recycle bin)
- `Sources/KeePassBarTOTP/` — Base32, otpauth parsing, KeePassXC's otpauth format, legacy TOTP fields
- `Sources/KeePassBarSearch/` — search rules, ranking, site matching with the public suffix list
- `Sources/KeePassBarPresentation/EntryCardBuilder.swift` and friends — how a card is laid out
- `SPEC.md` of the Mac app — the behaviour in words (§3.1–3.4, §3.6.2, §3.16–3.18, §3.22, §3.23)

## 3. The database file and Syncthing

The single most important part. The file lives in a folder Syncthing (Syncthing-Fork on Android) keeps in
sync; Syncthing replaces the file with a new one (writes a temporary file, renames it over the old one), and
may leave `<name>.sync-conflict-<date>-<time>-<device>.kdbx` next to it.

### 3.1 Access

All file access goes through one interface the core owns (`DatabaseStore`: read the bytes and a version
stamp, write by replacing, list conflict copies, watch for changes), so the way the app reaches the file can
be swapped without touching anything else. Two implementations are planned:
- **Side-loaded build: all-files access** (`MANAGE_EXTERNAL_STORAGE`, granted once in Settings; fine for a
  side-loaded app) and plain `java.io.File` paths. The app remembers the **path**, not a handle, so a file
  replaced by a rename is simply the file at that path. It can write a temporary file in the same folder
  and rename it over the database (atomic, as the Mac app's `replaceItemAt`), and `FileObserver` on the
  folder sees Syncthing's renames.
- **Google Play build (and fallback): the Storage Access Framework** with access to the **folder** (`ACTION_OPEN_DOCUMENT_TREE`,
  persisted), the file looked up by name on every read — never a persisted single-document URI, which is
  what goes stale. SAF cannot rename over an existing file, so writing is in place (truncate + write): a
  sync could pick up a half-written file. Play allows all-files access only for file managers, backup,
  antivirus and similar apps, so a password manager is expected to be refused it; a Play build would use
  this one. For the side-loaded build it is only a fallback if all-files access turns out not to work.

### 3.2 Reading and noticing changes

- The database is read on unlock and again when the file changed (size, modification time or inode; folder
  watcher while the app runs, and a check on every return to the foreground). The reload uses the
  in-memory key (§4), so it does not ask for anything.
- A file caught half-written (bad checksum / HMAC) keeps the current vault and tries again on the next
  change (as on the Mac: damaged ≠ wrong password).
- A missing file (Syncthing in the middle of a replace) is retried for a few seconds before it is reported.

### 3.3 Writing

The Mac app's save path, unchanged in substance (Mac SPEC §3.16 "How"):
1. Read the file again; open it as an XML tree that keeps everything (unknown elements, history, custom
   data, other apps' data) and writes it back in order.
2. Find the entry by UUID; if its `LastModificationTime` differs from the one loaded → "changed elsewhere",
   nothing is saved, the vault reloads. Other entries changed elsewhere are kept.
3. Apply the change as KeePass does (old version into `History`, trimmed to `HistoryMaxItems`).
4. Write: same format, new master seed / IV / inner stream key; attachments compacted, `Ref`s renumbered.
5. **Check before replacing**: open the new bytes with the built-in reader; every other entry and group
   exactly as before, the changed entry exactly as asked, every attachment byte for byte.
6. If the file on disk changed meanwhile (SHA-256), do it all again on the new file (once more, then fail).
7. Back up the old file into app-private storage (`filesDir/backups`, newest 10; not in the Syncthing folder)
   and replace: temporary file in the same folder, then rename. The temporary file's name must be one
   Syncthing ignores (to check: `.syncthing.*.tmp` and `~syncthing~*.tmp` are its own temporary names and are
   always ignored), so a half-written file is never sent anywhere.

### 3.4 Conflicts

- A `*.sync-conflict-*` copy of the database next to it is shown as a banner ("Syncthing found two versions:
  …") with its date. v1 does not merge: it says which one is open and that the other should be merged in
  KeePassXC (Database → Merge). Later: merge in the app.

## 4. Session and security

- Unlocked state holds the vault, the attachments and the **derived key** (not only the password) in
  memory, as byte arrays that are wiped on lock. Using the derived key for reloads and saves avoids running
  Argon2 again (the Mac ROADMAP's "faster saving"); it must be derived again when the file's KDF parameters
  or seed changed.
- Lock: after N minutes in the background (default 5; turning the screen off puts the app in the
  background), from a button, and when the app is removed from recents. Locking wipes key, password, vault
  and attachments, and removes the code notification (§6.3).
- `FLAG_SECURE` on every window that can show a secret (no screenshots, blank in recents).
- Clipboard: `ClipDescription.EXTRA_IS_SENSITIVE` (hides the preview on Android 13+); cleared after 30 s
  (fixed, not a setting) if it still holds what we copied (to verify: clearing from the background on
  Android 17).
- Attachments opened in another app go through a `FileProvider` URI of a temporary file in the app's cache,
  deleted on lock and on the next start.
- Network: none (no `INTERNET` permission at all in v1).

## 5. Biometric unlock

- Off until the first typed unlock with the switch on. Then the master password is encrypted with an AES-GCM
  key in the Android Keystore (StrongBox / Titan M2 when available), created with
  `setUserAuthenticationRequired(true)`, biometric-strong only, `setInvalidatedByBiometricEnrollment(true)`.
  The ciphertext is kept in app-private storage.
- Unlock: `BiometricPrompt` with a `CryptoObject` for that key; the decrypted password goes to the normal
  unlock, as if typed. Every later typed unlock re-encrypts it (a changed database password heals).
- A new fingerprint enrolled (key invalidated) or a stored password the database rejects → the stored copy is
  deleted, "type the password". Cancel → type the password, nothing deleted.
- Same idea as the Mac app's Touch ID (Mac SPEC §3.8), with the Keystore in place of the Secure Enclave.

## 6. The interface

Design, UI and UX matter as much as the file handling: the app must look and feel great, not merely work.
Screens are designed as clickable mock-ups first and agreed with the owner before they are built (ROADMAP
step "Design").

### 6.1 Look and feel

- Material 3 Expressive (Android 16+): spring motion, large expressive type, shapes; dynamic colour from
  the wallpaper; light and dark; edge-to-edge; predictive back.
- One-handed: the search field at the **bottom** above the keyboard.
- Entry icons: the database's custom icons (KeePassXC stores downloaded favicons) when present, otherwise a
  monogram with a colour derived from the site. Nothing is fetched (no network).
- Motion: the list row grows into the card (shared element transition); the one-time code ring runs down
  smoothly; copying gives a light haptic tick and a short confirmation.
- Passwords in a monospaced font with digits and symbols coloured, as in 1Password.
- Few settings, fixed sensible defaults instead of switches.

### 6.2 Screens
- **Lock**: password field, fingerprint button, reason of the last lock.
- **List**: search results (empty query: recently used, then all by title); a row = title, username,
  site; a long press copies the password.
- **Card**: as on the Mac (sections, masked values with an eye, live codes with a ring, tap to copy, links
  open the browser, attachments open / save). A password's menu: **Show as QR** (full screen, for another
  phone to scan; nothing leaves the device), **Large type** (full screen, big monospaced characters, for
  typing it on a TV or another computer), **Share…** (the system share sheet, after a warning that the text
  goes to another app and maybe its cloud). A code's menu: **Pin to notification** (§6.3).
- **Editor**: the Mac editor's rules (name above value for custom fields, protected switch, problems keep
  Save off; no reordering in v1), saving shows "Saving and checking…".
- **Delete**: moves the entry to the recycle bin at once, with "Deleted · Undo" in a snackbar; no
  confirmation dialog.
- **Recycle bin**: `-` in the search, Restore on the card.
- **Settings**: database file, biometrics, lock timeout.
- **Launcher shortcut** "Codes" (long press on the icon): unlock, then the list of entries with one-time
  codes.

### 6.3 One-time code in the notification shade

- "Pin to notification" on a code shows an ongoing notification: entry title, the current code, a countdown
  and a **Copy** action; the code changes every period while the notification is up (a foreground
  service). One pinned code at a time; a new pin replaces it; it can be dismissed.
- It lives only as long as the session: locking removes it and wipes the secret with everything else.
- On the lock screen the notification shows only "KeePassBar: code ready" (a public version without the
  code).

## 7. Autofill

`AutofillService` (Android's Autofill Framework); the user picks KeePassBar in Settings → Passwords &
autofill, and in Chrome sets "Autofill using another service" (Firefox needs nothing).

- **Fill**: from the `AssistStructure` find username / password / one-time-code fields (autofill hints first,
  then input types, ids and hints in the field's text); the target is the web domain (browsers) or the app's
  package name. Matching entries: the Mac app's site matching (registrable domain via the public suffix
  list, additional `KP2A_URL` fields; `androidapp://<package>` URLs as Keepass2Android writes them).
- **Locked**: the dataset is an "unlock KeePassBar" item that opens the unlock (fingerprint) and then
  fills.
- **Inline suggestions** in the keyboard (Gboard) where offered, else the dropdown.
- **One-time code**: a field recognised as a code gets the matching entry's current code.
- **Save**: `SaveInfo` for username + password; on `onSaveRequest` a screen asks: update the matching entry's
  password (when the username matches) or create a new entry (title from the site, URL, username,
  password, group chosen), written through the normal save path (§3.3). For an app, the URL is
  `androidapp://<package>`. For a sign-up the password field can be filled with a generated password
  (one screen: length, symbols on/off).
- Never fill into a field of an app whose package does not match the entry, and never offer a protected
  custom value.

## 8. Build and install

- Built on the Mac from the command line (JDK 21, Android command-line tools, the Gradle wrapper); Android
  Studio is optional.
- Release APK signed with the owner's own keystore (kept outside the repository, password not in files);
  installed with `adb install -r` (USB or wireless debugging) or by copying the APK to the phone.
- Updates keep the same signing key (otherwise Android refuses the update and the app must be uninstalled,
  which deletes the biometric key and settings).
- minSdk 34 (only the owner's phone and an emulator), targetSdk = the newest installed (Android 17).
- Google's developer verification for side-loaded apps (rolling out 2026–2027): check its state before the
  first install; installing over `adb` is expected to stay possible.

### 8.1 Google Play (maybe, the very last step)

Not decided whether it happens at all. If it does: the SAF storage (§3.1), a privacy policy, store listing,
and a donation option. External donation links are a grey area under Play's payment rules; the safe ways are
a "tip" through Play Billing or a separate paid "supporter" build (as KeePassDX does) — check the rules at
that time. A donation link, if any, comes only with the Play release.

## 9. Fixtures

`fixtures/` holds fictitious databases copied from the Mac project (password `pw`): KDBX 4 with AES + AES-KDF,
AES + Argon2d, ChaCha20 + Argon2id, a KDBX 3 one (must be refused as unsupported), `kdbx4-export.xml` (keepassxc-cli's XML export of
another fictitious database: two entries with attachments; KDBX 4 exports carry no `Meta/Binaries`) and `sample.xml` (hand-made, the real structure). The core's tests use them
the way the Mac tests do. Never use the real database in tests.
