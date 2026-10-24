# Keething for Android — spec

The application id is `dev.keething.android`, the packages `dev.keething.*`. The Mac app's settings entry is
the one titled "Keething" (the Mac still also reads one titled "KeePassBar", the apps' former name; this app
does not: rename such an entry to "Keething"). The field types are read under `Keething.FieldTypes`, else the former
`KeePassBar.FieldTypes`, which a write of the types removes (§3.3).

A small Android client for the same KeePassXC database the Mac app Keething (`~/Projects/keepass-bar`)
reads and writes: unlock with a fingerprint, search, view an entry with its custom fields laid out readably,
copy fields and one-time codes, edit and create entries, attachments, and autofill in apps and browsers —
with the system's "save this password?" offer after a login or sign-up.

Owner's context: one user, one database (`1Password.kdbx`, ~35 MB, ~1900 entries in ~14 groups, imported from
1Password, many custom fields, some one-time codes and attachments), password only (no key file, no
YubiKey), **synchronised with Syncthing** between the Mac and the phone. Phone: **Pixel 11 Pro, Android 17**;
checks on a phone run on a separate test phone (Pixel 6, Android 17).
Installed as a self-signed APK. Google Play is the last step (§8.1), not a v1 goal, and nothing
may block it: the storage choice in §3.1 is the one place where it matters.

Why not KeePassDX / Keepass2Android: KeePassDX keeps losing the database file when Syncthing delivers a
new version; Keepass2Android works but its interface is not liked. So the two things this app must do
better are: **never lose the file** (§3) and **a clean, readable, beautiful interface** (§6).

Not a kitchen sink: search, view, copy, edit, save, autofill, share a password or an entry — and nothing that
does not serve those.

## 1. Goals / non-goals

Goals (v1)
- Open the database from the Syncthing folder; notice new versions; never lose track of the file.
- Unlock with the master password, then with the fingerprint (§5).
- Search (as the Mac app: terms, fuzzy, `@group`, `#tag`, `+attachment`, `-` for the recycle bin).
- Entry card: title, group path, username, password (masked), URLs, one-time codes live with a
  countdown, notes, custom fields in file order with the Mac app's layout rules (sections, lists of codes,
  dates, links), attachments.
- Groups & tags (§6.2): the group tree and the tags, each with how many entries it has; a tap lists them.
- Copy any field; the clipboard is marked sensitive and cleared after 30 s.
- Attachments: open and save a copy. One-time codes: show and copy (setting them up is optional, later).
- Share a password (§6.2): as a QR code on the screen, in large type, or through the share sheet after a
  warning.
- Share entries encrypted for one phone (§10): the other side asks with a request, the answer opens only there;
  or sent without a request, opened by ten pictures given another way than the code. As a QR code, a picture or
  text, through any messenger or mail.
- Autofill (§7): fill logins in apps and browsers, fill one-time codes.
- Quick access to one-time codes: a "Codes" launcher shortcut, and the code on the clipboard after
  autofill filled a login (§7).

The first releases only read the database, so they cannot damage it; the owner rarely edits on the phone.
Writing comes after them (ROADMAP step 7) and must be done before a Google Play release:
- Edit, create, recycle and restore entries (delete is undoable from a snackbar; emptying the recycle bin
  is left to the Mac); custom fields.
- Autofill's offer to save a new login or update a changed password, with the password generator for
  sign-ups.
- The password generator (Mac SPEC §3.20): in the editor's password field, and on its own from the list
  (a password without an entry, copied).

Non-goals (v1): SSH agent, e-mail aliases,
reports (but Groups & tags, without its changes), restoring a version from the history, key file / YubiKey, KDBX 3, Twofish, merging conflicting copies (detected and shown
only, §3.4), deleting entries for good / emptying the recycle bin, adding / removing / renaming
attachments, reordering custom fields, a Quick Settings tile, a one-time code pinned to the notification
shade (dropped: the list's code button and the code copied after autofill do that job, and it would need a
foreground service for little gain), an entry pinned to the notification shade with Copy username / password /
code (dropped 2026-10-22: doable without a service, but it saves only a switch back to the app, whose card
stays open until the lock, autofill covers most logins, and it needs the notification permission; worth it
only for frequent copying into apps autofill does not reach), Google Play (§8.1).

## 2. Architecture

Kotlin, Jetpack Compose, Gradle (Kotlin DSL) with the wrapper checked in. A grown-up app built on best
practice: SOLID and DDD are the orientation, without fanaticism.

Gradle modules carry the architecture: each module is a feature or the shared kernel, and the allowed
dependency directions are the module dependencies, so the compiler enforces them.

```
core/domain   shared kernel, small on purpose: Entry, Vault, CustomField, Attachment, TOTPConfig,
              EntryChanges, VaultEdit, the secret value type, and the ports (DatabaseStore §3.1,
              DatabaseBackups §3.3, Clock, BiometricPasswordStore §5, SettingsStore, …).
              Depends on nothing.
core/totp     one-time codes: Base32, otpauth links, KeePassXC's formats, code generation   → domain
core/search   search rules, ranking, site matching with the public suffix list (a resource,
              `tools/update-public-suffix-list.sh`)                                       → domain
core/kdbx     the KDBX 4 file format: crypto, reader, writer, XML tree                    → domain
core/vault    application layer: open, edit, save with the checks, the lock / unlock /
              auto-lock state machine, the fingerprint unlock's rules    → domain, kdbx, totp
core/autofill autofill's rules: what a field asks for, the target, the matching entries (§7)
                                                                             → domain, search, totp
core/card     how an entry card is laid out and edited (the Mac app's Presentation rules: sections,
              dates, links, lists of codes, payment card, Wi-Fi, bank account, identity document,
              SSH, their faces and the offer to type them, IBAN / БИК checks, an SSH key's public half,
              deadlines, avatars; the editor's rules, `card/editing`) → domain, totp, search
core/generator the password generator: kinds, options, exact entropy, the EFF word list (a
              resource)                                                  → nothing
core/sharing  sharing entries (§10): HPKE, the request and answer formats, the pictures, the requests
              both ways and their sealed file                      → domain, kdbx (Argon2), totp
app           Android: the adapters behind the ports (storage, biometrics, clipboard, clock), Compose UI,
              AutofillService (§7), the composition root
                                 → vault, search, totp, card, autofill, generator, sharing (not kdbx)
```

A new module is added when a feature has its own rules and its own tests, not for every package.

Code design:
- Domain by feature: inside a module, packages are features (`kdbx/reader`, `vault/saving`,
  `app/unlock`, `app/entry`), never layers (`models`, `services`, `repositories`, `utils`). A feature's
  screen, state and adapters live together.
- Self-documenting through modules, types and names, not comments. A comment says only what the code
  cannot: why, a format quirk, a link to the Mac code it follows.
- Core modules use Kotlin's explicit API mode: what a module makes public is its contract; everything
  else is `internal` or `private`.
- Dependency injection by hand: constructors, and one composition root in `app`. No DI framework until
  that hurts.
- No fanaticism: no use-case class around a single call, no interface with one implementation unless it
  is a port (a side effect to fake in tests), no module per screen.
- Shared build setup lives in `build-logic/` (the `keething.kotlin-library` convention plugin);
  versions in `gradle/libs.versions.toml`.

Rules carried over from the Mac app:
- Dependencies point inward; anything with side effects (files, clock, clipboard, keystore, biometrics)
  is behind a port in `core/domain`, with fakes in tests.
- TDD: new behaviour starts with a failing test; tests are never loosened to make code pass.
- Fail closed on secrets: search, logs and autofill never see protected values except where a value is the
  thing being copied or filled.
- No `Log` of entry values or the master password, ever.

The Mac app's code (Swift) is the reference for every format detail: read it before writing the Kotlin
version. The key places:
- `Sources/KeethingKDBX/` — KDBXReader, KDBXWriter, XMLTree, EntryEditing, EntryLifecycle,
  AttachmentPool, Argon2, ChaCha20, BLAKE2b
- `Sources/KeethingLoading/KDBXFileSaver.swift` — re-read, apply, write, read back and verify
- `Sources/KeethingParsing/` — XML → Entry (which keys are custom fields, TOTP, attachments, recycle bin)
- `Sources/KeethingTOTP/` — Base32, otpauth parsing, KeePassXC's otpauth format, legacy TOTP fields
- `Sources/KeethingSearch/` — search rules, ranking, site matching with the public suffix list
- `Sources/KeethingPresentation/EntryCardBuilder.swift` and friends — how a card is laid out
- `SPEC.md` of the Mac app — the behaviour in words (§3.1–3.4, §3.6.2, §3.16–3.18, §3.22, §3.23)

## 3. The database file and Syncthing

The single most important part. The file lives in a folder Syncthing (Syncthing-Fork on Android) keeps in
sync; Syncthing replaces the file with a new one (writes a temporary file, renames it over the old one), and
may leave `<name>.sync-conflict-<date>-<time>-<device>.kdbx` next to it.

### 3.1 Access

All file access goes through one port in `core/domain` (`DatabaseStore`: read the bytes and a version
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

Decided by the storage experiment (ROADMAP step 1, on the Android 17 emulator with Syncthing-Fork 2.1.6 and the
Mac's Syncthing): the side-loaded build uses all-files access. The probe followed every change from the Mac,
a real conflict, deletions and the app's own writes without losing the file. The file's identity
(`BasicFileAttributes.fileKey()`: device and inode) works on Android's shared storage and catches a
replace that keeps size and time.

On the phone (Pixel 6, Android 17, Syncthing-Fork 2.1.3) reading behaved the same: changes from the Mac
(about 10 s later), a real conflict copy, the moment without a file during Syncthing's replace. Writing did
not at first: a file **replaced by a rename** got the replaced file's modification time back, and with the
size unchanged Syncthing took it for unchanged and never sent it: the phone and the Mac silently disagreed.
The cause is the phone's shared storage, not Syncthing (found in ROADMAP step 7): on the Pixel 6 (kernel
6.1) the first read of a file that replaced another by a rename gives it the replaced file's exact time.
Any read does it, by any process, in the Syncthing folder or in `Download`, with Syncthing stopped too;
one byte is enough, opening and closing is not; the content and size read back right. Earlier it looked
tied to the Syncthing folder only because nothing else read the file there. The emulator (kernel 6.12)
keeps the new time. The way out is in §3.3 step 7.

### 3.2 Reading and noticing changes

- The database is read on unlock and again when the file changed (size, modification time or inode; folder
  watcher while the app runs, and a check on every return to the foreground). The reload uses the
  in-memory key (§4), so it does not ask for anything.
- A file caught half-written (bad checksum / HMAC) keeps the current vault and tries again on the next
  change (as on the Mac: damaged ≠ wrong password).
- A missing file (Syncthing in the middle of a replace) is retried every second for 10 s before it is
  reported; the vault shown stays meanwhile.
  With file versioning on (it was on for the test folder on the emulator), Syncthing first moves the old file
  into `.stversions` and then renames the new one in, so for a moment there is no file.
- Shared storage is a FUSE file system: right after an inotify event `stat` can still show the old file
  (measured: stale at the event, fresh 0.3 s later). The folder watcher reports every event again after
  1 s (`FileObserverFolderWatcher`).
- One Syncthing replace brings several events (rename, then the modification time); a reload decrypts the
  file with Argon2 again, so the reload waits for the folder to be quiet for 1.5 s and runs once. A version
  that failed to open (damaged) is not read again until the file changes.
- An earlier vault stays intact until the lock (a card may still show one of its entries); the lock wipes
  every vault of the session.
- Histories are skipped: an unlock and a reload read no entry's `History`. An entry's history is read when
  its History screen opens (§6.2): the file once more with the in-memory key (no key derivation), keeping
  that entry's `History` items only (`KdbxReader.open(…, historyOf)`), every other secret of that read
  wiped at once and the attachments' bytes too; as the Mac does it (Mac SPEC §3.22), but streamed, so it
  costs the memory of a reload, not that of the save's XML tree.

### 3.3 Writing

What the phone writes (`VaultEdit`): an entry's fields (title, username, password, URL, notes, custom fields,
their types, tags, expiry), a new entry at the end of a group's entries, an entry into the recycle bin and back out of it.
Deleting for good, history, attachments, one-time codes (but that of an entry received, §10, written as
KeePassXC's `otp` link) and groups are left to the Mac (§1).

The Mac app's save path, unchanged in substance (Mac SPEC §3.16 "How"; `core/vault` `VaultSaver`, `core/kdbx`
`EditableDatabase`):
1. Read the file again; open it as an XML tree that keeps everything (unknown elements, history, custom
   data, other apps' data) and writes it back in order. A protected value goes into the tree as a secret,
   decrypted straight into it: the tree holds no password in the clear either (§4).
2. Find the entry by UUID; if its `LastModificationTime` differs from the one loaded → "changed elsewhere",
   nothing is saved, the vault reloads. Other entries changed elsewhere are kept.
3. Apply the change as KeePassXC does: the old version into `History`, trimmed to `HistoryMaxItems`; a new
   entry with KeePassXC's elements and its group's own icon; the recycle bin made as KeePassXC makes it when
   there is none (the file then becomes KDBX 4.1, for `PreviousParentGroup`); a restored entry into the group
   it came from, the root group when that is gone, and an entry recycled with its group into that group's
   place, the group made again there by name (as on the Mac).
4. Write: same format, new master seed / IV / inner stream key; the KDF parameters and salt kept, so the key
   in memory writes it and a save derives nothing; attachments nothing refers to dropped, identical ones
   stored once, `Ref`s renumbered, every reference still meaning the same bytes.
5. **Check before replacing**: open the new bytes with the built-in reader; every other entry and group
   exactly as before, the changed entry exactly as asked, every attachment byte for byte. "Before" is the
   reader's own reading of the same decrypted XML the tree was made from, so a fault in the tree shows as a
   difference.
6. If the file on disk changed meanwhile (byte for byte), do it all again on the new file (once more, then
   fail).
7. Back up the old file into app-private storage (`filesDir/backups`, `<name> <local date and time>.kdbx`,
   the newest 10 of each database; not in the Syncthing folder, and the app allows no Android backup, so they
   stay on the phone) and replace: temporary file in the same folder, then rename. The temporary file is
   `.syncthing.keething-<uuid>.tmp`: Syncthing never scans or sends `.syncthing.*.tmp` (its own downloads),
   and the random middle keeps it apart from the `.syncthing.<name>.tmp` Syncthing downloads into. Checked in
   the experiment: while the app wrote an 8 MiB file, the Mac's folder (sampled every 50 ms) never showed the
   app's temporary file, only the final one.
   Then the file's modification time is set through its path (`PathDatabaseStore`): the clock's time, but at
   least 2 s after the replaced file's, since Syncthing on Android takes times less than 2 s apart for equal
   (its `ModTimeWindow`, on any file system but ext2–4). A byte is read and the time checked: a time that
   did not stay fails the replace, although the content is replaced by then. Without this the Pixel 6 gives
   the new file the old time at its first read (§3.1); with it the time stays. Checked on the test phone: the
   shell's `mv` followed by `touch -m`, then reads for 75 s, Syncthing's scan among them; the probe's 8 MiB
   test file written three times with the same size through the app's code reached the Mac each time
   (11–12 s later, byte for byte). Writing in place would keep the time too, but gives up the atomic replace.
   Syncthing's own bug [#10924](https://github.com/syncthing/syncthing/issues/10924): a change on another
   device that only touches the time, arriving before the phone scanned its own new file (watcher delay
   10 s), sets that time on the phone's file and its content is never sent. Fixed in Syncthing 2.1.7 (a
   release candidate in October 2026); both phones run 2.1.3, so Syncthing-Fork is to be updated when it
   ships. Nothing the app can do; it needs a change that touches only the time, which KeePassXC never makes.

The session saves one change at a time with the key in memory (`VaultSession.save`); the vault is then the
written file as read back, and the app's own write is not read again. Refused because the entry changed or went
elsewhere, the vault is read again; refused because the password changed elsewhere, the app locks. Reloads and
saves take turns, so an older read never replaces a newer save. On the Mac's JVM a save of the fictitious
1,894-entry demo database took about 0.3 s, no key derivation; on the test phone (Pixel 6, release build,
the same database with the real KDF parameters) 0.66–0.75 s from Save to "Saved and checked", and about
115 MB PSS afterwards. Checked there: an edit, a delete, a restore and a new entry, each read by
`keepassxc-cli` on the Mac after Syncthing brought the file (within seconds of Syncthing running).

Checked in JVM tests (the Mac's writing, editing, lifecycle and saver expectations, on the fixtures) and with
KeePassXC's own `keepassxc-cli` (2.7, when installed): it opens every written fixture, exports a written file
exactly as the original, and reads an edited entry's protected fields, a new entry and the recycle bin.

On a copy of the owner's database (2026-10-21, on the Mac, `tools/kdbx-probe.sh`, core/vault `KdbxProbe`, the
Mac's `KDBXProbe --roundtrip` and `--lifecycle`; it prints counts and times, never a value): 34 MB, KDBX 4.1,
AES-256, Argon2d, 1,858 entries, 18 groups, 52 attachments. Read and written back unchanged, KeePassXC's exports
of both files were identical (55 places where two exports of the same file differ by themselves left out) and
the attachments byte for byte; then 8 saves through the save path, each checked (a new entry: created, edited,
into the bin, restored, into the bin again; the entry with the most fields, 44 and 3 attachments: its notes,
into the bin, restored), 1.65 s each on the Mac's JVM; read again from scratch, every other entry, group and
the bin as before, and KeePassXC opened it. Peak Java heap on the Mac 1 GB (`-Xmx2g`, not what the phone
needs at least). The copy was deleted afterwards.
The same copy on the test phone (Pixel 6, release build, in a folder outside Syncthing, then deleted with the
app's data and its backups): the unlock took 2.7 s; a new entry saved and checked in 3.0 s, its move into the bin
too; the Java heap peaked at about 383 MB during a save (sampled every half second; the app's large-heap limit
is 512 MB), 476 MB PSS. Enough, but not with room for a much larger database: a save holds the file, its
decrypted content, the tree, the new file and its reading back at once.

### 3.4 Conflicts

- A `*.sync-conflict-*` copy of the database next to it is shown as a banner ("Syncthing found two versions:
  …") with its date. v1 does not merge: it says which one is open and that the other should be merged in
  KeePassXC (Database → Merge). Later: merge in the app.

## 4. Session and security

- Unlocked state holds the vault, the attachments and the **database key** in memory, as byte arrays that
  are wiped on lock. The database key is the password's composite key (SHA-256 of its SHA-256: the password
  itself is not kept) and the key derived from it. Using the derived key for reloads and saves avoids running
  Argon2 again (the Mac ROADMAP's "faster saving"); it is derived again when the file's KDF parameters or
  seed changed. The app's own saves keep the KDF salt; KeePassXC gives the file a new one on every save (checked
  on 2026-10-23 with keepassxc-cli 2.7.12: a new salt after each of two saves), so the first reload after a
  KeePassXC edit derives the key again.
- Unlock time, measured on the test phone (Pixel 6, Android 17, release build) with a fictitious database
  of 1,895 entries (165 KB) and the owner's real KDF parameters (Argon2d, 64 MiB, 42 iterations,
  parallelism 2): 2.4 s for the first unlock in a process, 2.3 s once warm; the key derivation alone is
  2.1 s / 2.0 s, so it is the slow part, not cold code (on the emulator it was the other way round). The
  Kotlin Argon2 already fills the two lanes in parallel; a native Argon2 (e.g. argon2kt) is the remaining
  lever, not taken yet. Memory after the unlock: about 160 MB PSS, of it 78 MB Java heap
  (`android:largeHeap` is on).
- Secrets are wipeable byte arrays (`Secret`): passwords, protected fields, one-time code keys, attachments.
  Protected values are decrypted from the file straight into them, so the XML in memory never holds a
  password in the clear. Not wipeable, and accepted: a `String` while a value is shown, copied or filled;
  the typed master password in the text field until it is cleared; unprotected fields (username, notes);
  the values in the editor while it is open (Compose's text fields hold strings; the editor goes with the
  lock). What a save writes is made into secrets, wiped once the save is over.
- The keyboard is asked not to learn what is typed in the editor and the search field
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`, Gboard's incognito mode): entries' names and notes stay out of its
  dictionary. Password fields are never learnt anyway.
- Lock: after N minutes in the background (default 5, a choice of 1, 5, 15, 60; turning the screen off
  puts the app in the background), and from a button. Removing the app from recents ends its process, which
  takes the vault with it; leaving the app with Back is the background like any other (Android 17 finishes
  the activity then, so locking on that would ask for the password after every Back). Locking wipes the
  key, the vault and attachments.
- Android freezes an app in the background (about 10 s after it leaves), so the session's own timer may
  not run; an alarm (inexact, no exact-alarm permission) wakes the app at the timeout to lock it: on time
  or a little later, in Doze at the next maintenance window. Coming back checks the time too. Checked on
  the Android 17 emulator: the alarm thawed the frozen app and it locked. On the test phone (Pixel 6,
  screen off, Doze): Android froze the app anywhere from 16 s to never, and the session's timer locked in
  every run (1 min: after 62 s, also in forced deep Doze; 5 min: after 5 min 27 s in natural deep Doze).
  The alarm thawed the frozen app each time, but late: inexact, its window is about ¾ of the timeout (the
  5-minute one went off after 8 min 45 s). The check on coming back is what guarantees the lock on time.
- `FLAG_SECURE` on every window that can show a secret (no screenshots, blank in recents). A debug build
  only leaves it off while `files/allow-screenshots` exists (`adb shell run-as dev.keething.android touch
  files/allow-screenshots`), so the emulator can be checked with screenshots; a release build ignores it.
- Clipboard: secrets (protected and concealed values, one-time codes) are marked
  `ClipDescription.EXTRA_IS_SENSITIVE` (no preview on Android 13+); plain values (username, URL, notes) are
  not, so their preview shows. Every copy is cleared after 30 s (fixed, not a setting) if it is still the
  one copied: each copy carries a token in its description. A timer clears while the app runs, an alarm
  wakes a frozen app. In the background Android hides the clipboard from the app, so then it clears whatever
  is there, once per copy (a later copy in another app within those 30 s is lost; accepted). Checked on the
  Android 17 emulator: both ways clear, and the alarm even starts a new process when the old one is gone.
  On the test phone the timer cleared after 30 s in each run (screen off, Doze), and the clipboard was
  empty afterwards; reading it in the background is refused ("Denying clipboard access … not in focus" in
  the system log), clearing is not.
- Attachments (card rows "Open in another app" and "Save to a folder"; Mac SPEC §3.18):
  - **Open**: another app can only read a file, so the attachment is written to
    `cacheDir/attachments/<uuid>/<name>` (the name made safe: no `/` or `:`, no control characters, at
    most 255 bytes with the extension kept) and handed over as a read-only `FileProvider` URI with
    `ACTION_VIEW`; the system asks which app when more than one can. Those files are removed on every lock
    (the timeout too) and when the app starts (a process that died); a write that a lock overtook writes
    nothing. The one place where an attachment is on the phone's storage outside the database, as the Mac's
    Quick Look and KeePassXC's "Open" do it; the cache is app-private and in the phone's credential-encrypted
    storage. Removing the file does not take back what the other app already read (a PDF viewer keeps the
    page it shows) nor a copy it made; the app the user picks is trusted with it.
  - The type is the extension's; without a known one, text (valid UTF-8 without control characters, up to
    1 MiB) is `text/plain`, so an SSH key named `id_ed25519` or recovery codes open in a text viewer;
    anything else `application/octet-stream`. The provider tells the same type (a viewer asks it again: the
    HTML Viewer hung on a text file the provider called `octet-stream`).
  - **Save**: the system's save dialog (`ACTION_CREATE_DOCUMENT`) with the attachment's name; the bytes are
    written straight into the document chosen ("Saved scan.pdf"), no temporary file. Called off: nothing
    happens; when nothing can be written (locked meanwhile) the empty document is deleted again. If the
    vault locks while the dialog is up and the card closes, the dialog's result is lost and the empty
    document it made stays (accepted: the dialog takes seconds, the timeout minutes).
  - Not done, and why: serving the bytes from memory without a file (`StorageManager.openProxyFileDescriptor`).
    It would keep the attachment off the storage, but viewers then depend on a FUSE-backed descriptor whose
    support varies by app; the file is the plain, reliable way, with the cleanup above.
  - Checked: the file handling, names and types in JVM tests; on the Android 17 emulator a PDF (Drive's
    viewer), a PNG (Photos), a `.txt` and a text file without an extension (HTML Viewer) opened; a saved
    copy was byte for byte the attachment; a cancelled save left nothing; the files were gone after the lock
    button, after the 1-minute timeout with the PDF viewer in front, and after a restart. On the test phone
    (Pixel 6, release build): the PDF (Drive PDF Viewer), the PNG (Photos) and the text file without an
    extension (Chrome; the phone offered only text apps for it) opened; a copy saved into `Download` was byte
    for byte the attachment; after the lock button Chrome's tab of that file reloaded as "file not found".
    Files by Google's text preview takes the intent and closes at once, also for a `.txt` (it does not open
    other apps' `content://` text); the other apps open it.
- Network: none (no `INTERNET` permission at all in v1).
- Camera: only to read the code of a sharing request, answer or envelope (§10); the permission is asked when Read a code
  first opens, and reading a picture or pasting works without it.

## 5. Biometric unlock

Same idea as the Mac app's Touch ID (Mac SPEC §3.8), with the Keystore in place of the Secure Enclave; the
rules are a port of the Mac's `TouchIDUnlock` (`BiometricUnlock` in `core/vault`).

- **Off** until turned on, in Settings or with "Use fingerprint next time" under the lock screen's password
  field (shown while it is off and the phone has a strong biometric; the same switch). Turning it on only
  sets the switch: the next typed unlock that opens
  the vault stores the master password, and every later one stores it again, so a database password changed
  elsewhere heals the next time it is typed. A password typed while the vault is open, a wrong one, or one
  typed on a phone without an enrolled strong biometric is never stored. Turning it off deletes it, and so
  does choosing another database, which turns the switch off too: the password typed for the other database is
  stored only when it is turned on again (a copy of the real database opened on the test phone was stored
  unasked while the switch stayed on).
- **How it is kept** (`KeystorePasswordStore`): an RSA-2048 key pair in the Android Keystore, in StrongBox
  (Titan M2) when the phone has one, created with `setUserAuthenticationRequired(true)`,
  `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` (no time window: each use is one operation
  that the prompt unlocks) and `setInvalidatedByBiometricEnrollment(true)`. A random AES-256 key encrypts
  the password with GCM, and the public key wraps that AES key with RSA-OAEP (SHA-256, MGF1 with SHA-1,
  which the Keystore takes on every Android version). Sealing needs only the public key, so storing never
  asks for a fingerprint; opening needs the private key, which never leaves the Keystore. The sealed file is
  in `noBackupFilesDir`: never backed up nor moved to another phone, where no key could open it.
  An AES-GCM key in the Keystore (the first plan) would need a fingerprint to encrypt too: every typed
  unlock would ask for one to store the password again.
- **Unlock**: the platform `BiometricPrompt` (strong biometrics only, "Use password" as the way out: the
  lock screen's field is the fallback, not the screen-lock PIN) with a `CryptoObject` for the private key.
  The opened password goes to the normal unlock, as if typed, and is wiped right after. One prompt at a
  time. The lock screen offers it by itself once when the app comes to the foreground locked (a start, a
  return after the timeout), not after the lock button.
- **Discarding**: a fingerprint added or all removed, or the screen lock removed (the key is invalidated,
  found before any prompt is shown), or a stored password the database rejects → the stored copy and the
  key are deleted and the lock screen says to type the password; a rejected stored password does not shake
  the field. Cancel or "Use password" → the password field takes the focus, nothing deleted. A failed check
  (too many attempts) shows the system's words and keeps it; so does a database that could not be opened
  for another reason (missing, damaged).
- **The typed password now and then**: with the fingerprint on, the master password is never typed and gets
  forgotten (1Key asks again too). After 14 days without a typed unlock (Settings: 7, 14, 30 days or Never) the password
  is due: the lock screen does not offer the fingerprint, by itself or with its button, and says "It has been
  14 days: type the master password, so it is not forgotten" (after a problem, before the reason of the lock);
  the field takes the focus. The next typed unlock that opens the vault starts the days again, and the
  fingerprint is back; a fingerprint unlock, a wrong password or the cancelled prompt do not count. The stored
  copy stays meanwhile. The days count from the last typed unlock or from turning the switch on, on the wall
  clock; with no time saved yet (turned on before this rule) or one in the future (the clock set back) they count
  from now: it is a reminder, not a barrier, the fingerprint already says who it is. Never is there for those who
  type the password elsewhere every day (the Mac app); Settings then says in red that a forgotten password
  opens the database for nobody. One setting and one time for the app, not per database.
- Not done, and why: `setUnlockedDeviceRequired` (the prompt cannot be shown over the lock screen anyway);
  a marker of the enrolled set like the Mac's `evaluatedPolicyDomainState` (the Keystore's invalidation does
  that job here). Unlike the Mac, the key is bound to the app: the Keystore keeps it per app (UID).
- Checked: the rules with a fake store, the file format with a software RSA key (JVM tests); on the Android
  17 emulator with a simulated finger every path above (a software key there). On the test phone (Pixel 6,
  release build): the key is created in StrongBox, in 2.9 s, after the typed unlock, out of sight; the
  fingerprint unlock, the prompt by itself after a start, "Use password"; the lock screen's "Use fingerprint
  next time" (the typed unlock stored the password, the next one took the fingerprint). Opening the stored password takes
  0.3 s in StrongBox, then the unlock its usual 2.3 s (the key derivation). Android hands the app the accepted
  fingerprint about 0.9 s after the sensor took it (measured 2026-10-21, the prompt closing; nothing the app
  can shorten); from then on the lock screen says "Opening …", the StrongBox part included
  (`BiometricState.opening`). A new fingerprint invalidating
  the key was checked on the emulator only.

## 6. The interface

Design, UI and UX matter as much as the file handling: the app must look and feel great, not merely work.
Screens are designed as clickable mock-ups first and agreed with the owner before they are built. The
agreed mock-ups (light and dark, every screen of §6.2 including the editor for step 7) are the canvas
https://claude.ai/artifact/VvguNamsGWxY4zQwsHUaEa. Choices made there:
- Lock: the fingerprint is the large main button; the password field below it, in thumb reach, with an
  arrow button to unlock; the reason of the last lock as a chip.
- List and card: Expressive segmented groups (rows with small gaps, large outer corners); a list row with a
  one-time code shows the live code and its ring as a button that copies it.
- Card: a tinted tile per field kind; the password row has an eye, copy and a menu; recovery codes are a
  numbered grid with one eye; a copy confirms in a snackbar ("Password copied · clears in 30 s").
- Show as QR can also show the password as text; the Share… warning points to Show as QR as the safer way.
- The canvas also has a code pinned to the notification shade; it was dropped (§1).

### 6.1 Look and feel

- Material 3 Expressive (Android 16+): spring motion, large expressive type, shapes; dynamic colour from
  the wallpaper; light and dark; edge-to-edge; predictive back.
- One-handed: the search field at the **bottom** above the keyboard.
- Wide screens (agreed on the canvas, rows "Wide screens"): from 840 dp of window width (a tablet in landscape, an
  unfolded foldable, a phone in landscape too) the list and the card are side by side: the list a 360 dp pane (400 dp
  from 1000 dp), the card a rounded pane a shade darker beside it, without a Back arrow; the list marks the open
  entry's row, and nothing grows from row to card. Before an entry is chosen the card's pane says "Choose an entry:
  its card opens here." (no card opens by itself, so no password shows without a tap). A tap on another row takes
  the card's place; Back closes the card, then leaves the app. Recent keeps its order while a card is open beside
  it, so that the row tapped does not jump to the top under the finger; it catches up when the card closes.
  Everything else (the editor, a new entry, History, Show as QR, Large type, Groups & tags, Settings, Sharing) covers
  the whole window: the editor so that a tap in the list cannot leave unsaved changes behind, and in landscape the
  keyboard wants the height. A card opened from Sharing covers the whole window too (the list is not under it). On
  a wide window a screen's content is at most 640 dp wide and centred (the card's rows too, the bars stay full width);
  a face at most 440 dp, its buttons under it; the lock screen at most 480 dp. A narrower window keeps one pane
  (AndroidX `material3-adaptive-navigation3`, its list-detail scene).
- Entry icons: the database's custom icons (KeePassXC stores downloaded favicons) when present, otherwise a
  monogram with a colour derived from the title, the same as on the Mac. Nothing is fetched (no network).
- Motion: the list row grows into the card (shared element transition); the one-time code ring runs down
  smoothly; copying gives a light haptic tick and a short confirmation.
- Passwords in a monospaced font with digits and symbols coloured, as in 1Password. The font is Google
  Sans Code, bundled (SIL Open Font License, `assets/licenses`): every character must be clear.
- Large text and display sizes (checked at font scale 1.3 and density 500): a button's label never takes two
  lines (it gets smaller, then drops its icon, then takes a short label: "Ask for an entry" becomes "Ask"); two
  buttons side by side go one above the other when their labels do not fit whole; a segment's or a small bar's
  title and subtitle keep one line, smaller before they end in "…"; chips that do not fit go to a next line; a
  chip beside a row's text goes under it where it would leave the title less than about seven and a half
  characters (as a list row's code, §6.2). A " · " never starts a line, a date or "722 ms" is not broken, and a
  shown password wraps at the line's end, not after a `/` or a `-` as if it were two words.
- The generator's words are the EFF large word list (CC BY 3.0 US, `assets/licenses`). A recovery phrase is checked
  against BIP-39's ten word lists (MIT, `assets/licenses`; 184 KB, read when first needed).
- QR codes are made by Nayuki's `qrcodegen` (MIT, `assets/licenses`; 38 KB, no dependencies, medium error
  correction as on the Mac). The sharing's codes (§10) are read by ZXing (Apache 2.0, `assets/licenses`) from
  the camera (CameraX) or a picture: no Google Play services.
- Few settings, fixed sensible defaults instead of switches.

### 6.2 Screens
- **Choose the database** (first start, or from the lock screen and Settings): all-files access, the folder
  in the system picker, the `.kdbx` file in it (conflict copies left out).
- **Lock**: the app's logo (the launcher icon's layers), password field (a password keyboard: nothing capitalised, corrected or learnt), the database
  with its folder and when it changed (a tap chooses another), reason of the last lock. When the fingerprint
  is set up (§5), its large button with "Touch to unlock" and "or type the password" above the field; the
  field then does not open the keyboard by itself, and typing hides the button. While it is off, a "Use
  fingerprint next time" switch under the field turns it on, so the password typed then sets it up. When the
  password is due (§5) the button is not there and the reason chip says so. A wrong password shakes the
  field. The first list after an unlock says how long the key derivation took. On a screen less than 480 dp high
  (a phone in landscape) the logo, name and database are on the left and the field on the right; with the keyboard
  up only the database's line stays beside the field.
- **List**: search results (empty query: recently used, then all by title); a row = title, username,
  site, and under them the group's path with a folder (`Finanzen › Banken`; none for the root group, the
  path inside the bin for an entry in it, as the Mac's rows). The header (large "Keething", the database and its
  entries; Lock first and set apart, so that a tap meant for another icon does not lock, then Groups & tags,
  Sharing (§10; a dot while requests wait for an answer) and ⋮ with "Generate a password" (the editor's wand) and
  Settings) collapses into a compact opaque bar while the list scrolls, and comes back at the top; it collapses
  too as the search field takes the focus (the keyboard takes half the screen), and comes back when the field
  lets it go with the list at its top. On a screen less than 480 dp high (a phone in landscape) it is the compact
  bar from the start; beside the card (§6.1) that bar has the title only. Recently used: the last 8 entries whose card was opened or from which something was copied,
  kept as ids only (they survive a lock and say nothing without the database).
  **Search field**: a magnifier and "Search"; while it has the focus, chips over the keyboard: `@ group`, `# tag`,
  `+ file`, `− bin` (the bin's toggles the recycle bin search; none in the Codes list). A tap on one adds its sign
  to the query, and then the chips are what the filter being typed can be, as the Mac's suggestions (Mac SPEC
  §3.6, `FilterCompletion`, `SearchFacets`): the groups by path in tree order, the tags, the file names (a bare `+`
  first "Any file"), each with its entry count; inside the bin search its own groups and tags. A tap puts the name
  in (quoted when it has a space) with a space after it; none matching, no chips. Closing the keyboard ends the
  focus and the chips. Back closes the keyboard first, then clears the search, then leaves the Codes list.
  **Gestures**: a row swiped right copies the password, swiped left the username; past 88 dp what is under the row
  turns solid with a tick, and let go the row springs back (a side with nothing to copy says "No password" / "No
  username" and does nothing; both are accessibility actions too). A long press opens the **quick actions**:
  copy the username (shown), the password or the one-time code (live), open the site (as the card's link), then
  Edit, Remove from Recent (a recent row: "Removed from Recent" with Undo, which puts it back in its place, the
  entry itself untouched) and Move to the recycle bin (as Delete on the card, with Undo); an entry in the bin has
  only the copies and the site. A row's code is a pill beside the title, or under it where the pill would leave
  the title less than about seven and a half characters (large text on a narrow phone).
  A row also shows the paperclip of attachments and a date that has ended or ends within 90 days. A
  payment card's, a Wi-Fi network's, a bank account's, an identity document's, an SSH login's or a crypto wallet's
  row (its fields typed, §6.3) shows the small face instead of the icon (a card in its network's colours, the label
  of a router, a bank with the country's flag at its corner, a small paper document, a terminal's `>_`, a small steel
  plate with the network's badge at its corner) and says which it is instead of the username and site
  (`Visa •• 4242 · 12/26`, `WPA2 · FRITZ!Box 7590`, `DE89 •• 3000 · Commerzbank`, `Passport · Germany · until
  03/2029`, `alex@db.example.com:2222`, `Bitcoin · bc1qar…5mdq`, `Crypto wallet · recovery phrase`; a protected
  number gives no digits); the card's
  header shows it too (`EntryFace`). A
  group or tag chip on the card searches for it; Back clears the search first. A Syncthing conflict copy
  and a reload that failed (the vault read before is shown) are banners above the list.
- **Card**: as on the Mac (sections, masked values with an eye, live codes with a ring, tap to copy, links
  open the browser, attachments open in another app / save to a folder, §4). Every row that copies on a tap
  has the same copy button (username, URL, code, custom fields alike). **Edit** is a button at the bottom
  right, in reach of the thumb; the bar's ⋮ holds History, Send to a request (§10) and Move to the recycle bin
  (at once, the list offering Undo). In the recycle bin: Restore in the bar, History under ⋮, no Edit. Custom fields are read by their
  types (§6.3): a field typed `password` is masked with the password's key in front, protected or not. The header
  is compact: the icon (56 dp) beside the title, the group and tag chips under the title. Its bar (Back) is clear at the
  top and opaque once the card scrolls under it; when the large title has gone under, the entry's icon and
  title move into the bar. A password's menu (⋮ on its row): **Show as QR** (full screen at full
  brightness, black on white, for another phone to scan; nothing leaves the device; "Show the password"
  adds it as coloured text; a password too long for one code says so), **Large type** (full screen, each
  character in its own numbered cell, big, monospaced and coloured, a space shown as `␣`, for typing it on a
  TV or another computer), **Share…** (the system share sheet with the password as plain text, after a
  warning that it goes to another app and maybe its cloud and history; the warning offers Show as QR). Both
  full-screen views read the password from the vault by the entry's id, close on a lock, and are left with
  Back or ✕. The share sheet shows the text and has its own Copy, which the app cannot mark sensitive or
  clear: the warning comes first for that reason.
- **Entry faces** (agreed on the canvas, rows "Entry faces", "More faces" and "Crypto wallets"): a payment card, a
  Wi-Fi network, a bank account, an identity document, an SSH login and a crypto wallet are drawn as what they are,
  and the face is the interface; the rows under it are only what the face does not show. The Mac draws a card's and
  a network's fields as a card and rows (Mac SPEC §3.13): their data rules are the Mac's (`PaymentCard`,
  `WiFiNetwork`), the faces are the phone's. The bank, document, SSH and crypto types are the phone's (§6.3) until
  the Mac takes them over (`~/Projects/keepass-bar/HANDOVER-more-faces.md`, `HANDOVER-crypto.md`); the Mac keeps
  them and shows those fields as rows meanwhile.
  - **Payment card**: a card 1.586:1 (at most 440 dp wide, as every face, §6.1), in its network's colours (Mac `PaymentCardView`;
    Visa, Mastercard, American Express, Mir; another network in the entry's colour), with a chip, the contactless
    mark and the network's mark drawn simply. Its front: the number masked to its last four digits, the holder,
    "VALID THRU" and the month (red when past); a tap on any of them copies its field as stored. Its back: the
    magnetic stripe, the signature strip and the CVC, a tap copies it. **Show** reveals the number and the CVC
    together; **Turn over** or a sideways swipe on the card turns it, animated around its vertical axis. A
    chip under it says "Expires in 2 months" / "Expired 3 days ago" when the expiry is within 90 days or past,
    the only place the card says it (no banner for that field, no row). A number failing its check digit says
    so ("a typo?"). On the face (`PaymentCard.faceRowIds`): the number, an unprotected holder, an expiry that is
    a date, the CVC, and the type when the face names the card by it (a known network, or a protected number);
    the rest (the PIN, other `card.*` fields) are rows under it, with copy buttons.
  - **Wi-Fi network**: the label under a router: "WI-FI NETWORK" with the security as a chip ("WPA2", or "Open ·
    no password" in the warning colours), the network's name large, the key masked (coloured when shown), and the
    `WIFI:` QR code beside them (Mac SPEC §3.13; `EntryCard.wifiQrPayload`). The code holds the key, so it is
    blurred (drawn without the key) until the key is shown: a tap on it, or **Show**, reveals the key and the
    code; a tap on the code opens it full screen. A tap on the name or the key copies it. **Add to this phone**
    hands the network to the system's "Save this network?" (`ACTION_WIFI_ADD_NETWORKS`, a `WifiNetworkSuggestion`,
    WPA2 or WPA3 by the label, no permission; not for WEP, which Android no longer adds); a key Android refuses
    says so. The square **QR code** button opens it full screen too: a view like Show as QR, at full brightness,
    "Join <network>", reading the entry from the vault by its id and closing on a lock (`PasswordView.WIFI_QR`).
    Masking the key, leaving the card or a lock blurs the code again; an open network's code is always shown.
  - **Bank account** (`BankAccount`, an entry with a field typed `bank.iban` or `bank.account`): a label like the
    router's, "BANK ACCOUNT" with the country as a chip (its flag and English name: from the IBAN's first two
    letters, else the BIC's fifth and sixth, else a Russian БИК, each of which says it for sure; a US routing number or a
    Chinese bank code alone does not, and then the chip is Antarctica's flag and "Country unknown", as the list's
    small face; never a guess), the holder large, the number in the accent's monospace (an IBAN in groups of four, its country
    and check digits in the accent; a Russian 20-digit account as printed, `40817 810 5 0000 1234567`), then the
    BIC, the bank code ("BIK" for a Russian БИК, else "BANK CODE"), the correspondent account and the bank as
    small lines. A tap copies a line; the number and the correspondent account copy **without spaces** (bank forms
    often refuse them or cut them off), and say so in the notice. **Copy IBAN** / **Copy account** copies the
    number; **Share** sends the holder, number, codes and bank as text to the share sheet (for someone who sends
    money; nothing secret, so no warning; Russian details with their Russian names: Счёт, БИК, К/с). An IBAN
    failing its mod-97 check (ISO 7064), or of the wrong length for its country, says so ("a typo?"); a Russian
    account checks against its БИК (the Bank of Russia's key: the БИК's last three digits and the account,
    weights 7-1-3), the correspondent account against `0` and the БИК's fifth and sixth digits. A protected number
    is masked on the face with Show; other protected parts, and the recipient's ИНН / КПП, are rows under it.
  - **Identity document** (`IdentityDocument`, an entry with a `doc.number`): a card of paper (ID-1, 1.586:1 at
    least, taller with large text; a guilloche drawn behind; paper in either theme), its type as the heading
    ("PASSPORT", else "IDENTITY DOCUMENT"), the issuing country with its flag (a code is named: `D`, `DE` or `DEU`
    → Germany; a name in English, German, Russian, French, Spanish or the phone's language gives the flag; a
    country it cannot name gets Antarctica's flag), the
    name, the number in monospace (masked with Show when protected), born / issued / expires. A tap copies a line.
    No photo: what such entries keep is a scan of the whole document (a PDF or a picture), not the portrait; it
    stays an attachment. The expiry is a chip under it when within 90 days or past (no banner for that field), and
    it is a deadline like any `until`.
  - **SSH** (`SshLogin`, an entry with an unprotected `ssh.host`): a terminal, dark in either theme: "SSH" with a
    chip (`key · port 2222`, or `password`), the command (`$ ssh -p 2222 alex@db.example.com`; `-p` left out for
    port 22; `-i <file>` for a key in a file; words the shell would split quoted), host / user / port (the user is
    the `ssh.user` field, else the one written in the host, else the entry's username, marked "the username"), the
    key when the entry has KeePassXC's SSH agent settings (Mac SPEC §3.15: the `KeeAgent.settings` attachment names
    an attachment of the entry or a file on the computer; unreadable, the only key-like attachment): its name, type
    and `SHA256:` fingerprint as `ssh-keygen -l` prints it, read only up to the public key, which the OpenSSH format
    keeps in the clear even under a passphrase (`SshPublicKey`, the Mac's `OpenSSHPrivateKey.publicKey`); a tap
    copies the `authorized_keys` line. Then a `~/.ssh/config` block with its own Copy (`Host` from the entry's title,
    `HostName`, `User`, `Port`; a key in a file as `IdentityFile` with `IdentitiesOnly yes`; an attachment's key as a
    comment, the agent gives it to ssh). The phone runs no agent. A tap copies a line; **Copy ssh command**; **Open
    in <app>** when an app on the phone opens `ssh://` links (ConnectBot, JuiceSSH; `ssh://user@host:port`), "an SSH
    app" when several do. The `ssh.host` field takes `host`, `user@host`, `host:port`, `[v6]:port` or an `ssh://`
    link.
  - **Crypto wallet** (`CryptoWallet`, an entry with a `crypto.seed`, `crypto.address` or `crypto.xpub`): the
    recovery phrase on a steel plate (brushed steel in either theme, as such phrases are kept), hidden: a lock,
    "Whoever has these words has the wallet", **Show** and **Word by word**. The phrase is read only then (it is a
    secret like the password): shown, a numbered grid of its words, three to a line (two with large text), its
    count and language as a chip, and its check (`SeedPhrase`): a BIP-39 phrase's checksum in whichever of the
    BIP's ten word lists its words are in (MIT, `assets/licenses`; a typo in any word is caught: "Word 7, “worht”,
    is not a BIP-39 word. A typo?", outlined in red; "The checksum fails: a word is wrong, or two are swapped"; 12,
    15, 18, 21 or 24 words); an Electrum seed (Electrum's seed version, its HMAC-SHA512) is named as one, not failed.
    Numbers and commas written between the words are left out, words of the Latin lists count without their
    accents, and a word is shown as its list spells it. The passphrase (the "25th word") is a line on the plate with
    its own eye. **Neither is ever copied** (the clipboard is where phrases are stolen): no copy button, no tap to
    copy, no QR code, no share; in the editor its value is masked and its text menu has no Copy, Cut or the other
    apps' items ("Ask Gemini", "Translate"), only Paste and Select all; the history masks it even when not
    protected. **Word by word** is a full-screen view, a word at a time, large, with its number, the words before
    and after it, a bar of where it is, Back / Next and sideways swipes (read from the vault by the entry's id, as
    the password's views). An extended key typed `crypto.xpub` that is private (`xprv…`) is masked and never copied
    too, with a note saying why. Then the first unprotected receiving address as a label: the network read from the
    address's format with its badge (`CryptoAddress`: Bitcoin `1…`, `3…`, `bc1q…`, `bc1p…` with Base58Check or
    Bech32 / Bech32m; its testnet; Litecoin; Dogecoin; Ethereum `0x…` with EIP-55's mixed-case checksum (Keccak-256),
    "or another EVM network"; Tron `T…`; Solana by its shape, which has no checksum), the kind ("Native SegWit
    (bc1q…)"), a chip "checked" / "EIP-55" / "no checksum" / "fails its check" (with a note), its QR code (the plain
    address; a tap opens it full screen) and the address in groups of four, the first two and last two bold, to
    check where it is pasted (malware swaps addresses on the clipboard; the copy notice says so). **Copy address**,
    **Show QR**: full screen at full brightness with "Only Bitcoin to this address…", Copy and Share (an address
    is public: no warning). Under it the other addresses (badge, start and end, QR, copy), the extended public key
    (`zpub6rFR7y4Q2A…2AGutZYs`, "Shows every address and balance of the wallet: share it with care"; its checksum),
    the derivation path with what it says ("BIP-84 · Bitcoin, native SegWit · account 1"), then the rest.
- **Editor** (the card's Edit; a new entry from the button next to the search field): title, username,
  password (masked, an eye shows it coloured; masked, it shows the card's fixed dots, not its length, and a dot a
  character only while typed in), website, notes, tags, KeePass's expiry (a switch and a day; the time of day kept), and the custom fields:
  name above value, each with the row's whole width, and under them the type as a chip (§6.3), a Protected chip
  (a lock, closed when on) and remove; a protected value is masked as the password is. The text menu of the
  password, a protected or password-typed value and the lock screen's master password has no items other apps add
  ("Ask Gemini", "Translate", "Read aloud": they would hand it the text); the master password's and a recovery
  phrase's have no Copy or Cut either. **Tags** (Mac SPEC §3.24) are chips with ✕
  and a field after them, "Add a tag" (a tap anywhere in the box types one): Enter, a comma or a semicolon
  adds it (not when the entry has it already, in any case or accent), Backspace in the empty field takes
  the last chip off; typing offers the vault's tags that start with it (most used first), then those it
  matches fuzzily as the search does ("trvl" → Travel), those on the entry left out, as chips under the
  field, a tap adds one. What is still typed when saving is saved too. **Add field** is a menu: Text, Date, Date
  until (expiry), URL, Password, *Card ›*, *Wi-Fi ›*, *Bank account ›*, *Document ›*, *SSH ›* and *Crypto ›* parts (the
  menu shows the parts in its place, with a way back) add a field of that type named after it (Text, Date, Valid
  until, Website, "Password 2" as KeePass keeps "Password" for itself, Card number, Cardholder, Expiry date, CVC,
  PIN, Card type, Network name, Wi-Fi password, Security, IBAN, BIC, Bank, Account holder, Account number, Bank
  code, Correspondent account, Document type, Document number, Full name, Issuing country, Date of birth, Date of
  issue, Host, Port, SSH user, Recovery phrase, Passphrase, Address, xpub, Derivation path; "CVC 2" when the name is
  taken); the cursor goes into its value and the form scrolls
  to it above the keyboard. **Codes…** takes a block of codes pasted or typed (numbering and bullets dropped,
  at most 50) and adds them as protected fields `<list>_1`, `_2`, … typed `code`, which the card shows as a
  numbered list; such a list is folded into one row in the editor ("Recovery codes · 10 codes · protected")
  until tapped. The Mac editor's rules (`card/editing`): only what differs
  from the entry as loaded is saved; a field left blank is not saved; a field with a value needs a name, not
  one KeePass uses itself and not one another field has, said at the field and in a "Save is off" box; a new
  entry needs a title or a website and goes into the group chosen ("Top level", the root group, to start
  with; an existing entry is not moved). Save shows "Saving and checking…"; saved, the editor goes back to the
  card (a new entry's card) at once, which says "Saved and checked" in a snackbar; not saved, the sheet says
  why, with "Back to editing" (save from autofill: a toast, and back to the app). ✕ or Back with changes asks "Discard the changes?". Not in v1: reordering fields.
- **Password generator** (Mac SPEC §3.20, `core/generator`): a sheet from the wand in the editor's password
  field ("Use this password" puts it in, shown) and from ⋮ › Generate a password in the list's header (a password
  without an entry: Copy, sensitive and cleared after 30 s). Kinds PIN (4–16 digits), Strong (8–64
  characters, A–Z / a–z / 0–9 / symbols each at least once, look-alikes `0 O o 1 l I |` optionally left
  out), Words (2–8 EFF words in Capitals or CAPITALS joined by digits and symbols, at least the chosen
  number of each) and Phrase (3–10 lowercase words joined by `-`, space, `.` or `_`); the password
  monospaced with digits and symbols coloured; the recipe's exact bits (weak under 40, strong from 60) and
  length. `SecureRandom`; "at least one of each" by drawing again, so every valid password is equally
  likely. The options are remembered (`passwordGenerator` in the app's settings), never a password. The word
  list is a Java resource found by its absolute path: R8 renames the package in a release build (a relative
  path crashed the first phone run).
- **Delete** (the card's ⋮ › Move to the recycle bin): moves the entry to the recycle bin at once, no question; the card
  closes and the list says "Moved “…” to the recycle bin" with Undo (10 s), which restores it. The list
  keeps its scroll position: the row fades out at once (not when the save is done) and the rows below close
  up; a failed save or Undo puts it back in its place. A vault read again (a save, a sync) is listed while
  the one before stays on screen, so no reload empties the list or scrolls it to the top.
- **Groups & tags** (the folder in the list's header; the Mac's Groups & Tags window, §3.25 there, without
  changing anything): **Groups** as segmented rows, "Top level" (the root group) first, then tree order
  (parents first, siblings by name, indented by depth), each with how many entries are in it and below it
  and, when some are in its groups, how many right in it ("3 here"); empty groups too. The counts are those
  of the list (the Mac app's settings entry left out). Then the **Recycle bin** with its count, when it has
  entries. **Tags**: every tag outside the bin by name with its count, spellings differing only in case or
  diacritics as one ("also bank, Bänk"). A tap goes back to the list searching for it, as the card's chips
  do: `@Work`, `#bank`, `-` for the bin, "Top level" the whole list; Back clears the search. The search
  matches by name prefix at any level (§1), so `@Work` also lists a group "Workshop" or another "Work"
  elsewhere. The screen follows a vault read again.
- **Used recovery codes** (a list of codes on the card, an entry outside the bin): copying a code offers **Mark
  as used** in its notice; a long press on a code offers "Mark as used" / "Mark as not used". The mark is
  written into the field's value, in front of the code: `used 2026-10-22 · 1234-5678` ("used" in any case, the
  day in ISO 8601, then ` · `, U+00B7 with a space each side; `card/UsedCode`). So KeePassXC and the Mac show
  it as it is, a field renamed anywhere keeps it, and taking it off gives the code back unchanged. It is saved
  as an ordinary edit of that one field (protected as it was, the old version into the history, SPEC §3.3),
  with a progress ring on the code meanwhile and a notice with Undo after. Only the codes of a list are read
  so; another field with such a text stays text. On the card a used code is "used Oct 22, 2026" alone while the
  codes are hidden, and struck through above that line when they are shown; the list's title says "3 of 4
  left" (in the error colour when none are); copying a used
  code copies the code alone. Under the title a bar of one segment per code, on one line however many (thinner for
  more, in the error colour when two or fewer are left), says how many are left; the next unused code is outlined
  and **Copy next unused · 4** copies it (its notice offers Mark as used, as any code's does). The editor shows
  the value as it is stored. The Mac app's side:
  `~/Projects/keepass-bar/HANDOVER-used-recovery-codes.md`.
- **History** (the clock in the card's bar, for an entry in the bin too; Mac SPEC §3.22 without restoring):
  "Reading the file…", then the versions newest first, each dated by its modification time, the current one
  marked, each with what it changed from the one before (`card/history`, the Mac's `EntryVersionDiff`):
  title, username, password, URL, notes, custom fields (added / removed / changed, a change of protection
  or of type, the order), tags, expiry, the one-time code (that it changed, never its values), attachments
  (added / removed / size). Old values struck through above the new; protected ones masked, an eye each.
  The oldest version kept has nothing to compare with; "No earlier versions are kept" when there are none.
  The versions are wiped when the screen goes (a lock closes it); the values compared are plain strings
  while it is open, as in the editor.
- **Recycle bin**: `-` in the search; a card in the bin has Restore instead of edit and delete ("Restored to
  Web & Mail").
- **Settings**: database file, the fingerprint switch (§5; it says where the key is: StrongBox, the TEE) and,
  while it is on, how often to ask for the master password (7, 14, 30 days, Never; §5),
  lock timeout, how long a sharing request and a sending's pictures last (1, 3 or 7 days, §10); the last key derivation and the memory used, to check them on the phone; the storage probe
  in a debug build.
- **Launcher shortcut** "Codes" (long press on the icon; static, `res/xml/shortcuts.xml`): unlock, then
  the list titled "Codes" with only the entries with a one-time code, by title, each row's live code a
  button that copies it; the search searches among them. Back (or the arrow) goes to the whole list; a lock
  keeps the Codes list, so the shortcut opens the lock screen and then it. A group or tag chip on a card
  leaves it.


### 6.3 Field types

As on the Mac (Mac SPEC §3.12, §3.13; `core/card` `FieldTypes`, `FieldInterpreter`): what a custom field
holds is chosen per field in the editor and kept in its entry, in one item of the entry's `CustomData`
(`Keething.FieldTypes`, a JSON object field name → type name, keys sorted; written after `AutoType`, before
`History`; the element goes when its last item does; an item's `LastModificationTime` kept up to date when it
has one). KeePassXC keeps it through its own edits. The values stay plain text.

- **Types**: `text` (nothing is read from it), `date`, `until` (a date something ends on: the expiry hint and
  a deadline), `url` (a link, also without its scheme), `code` (one of a list of codes: a titled section of
  them is a numbered list at any size), `password` (masked on the card, protected or not; the editor masks
  it too and offers the generator, as for `wifi.password`), and the parts of a payment card (`card.number`,
  `card.holder`, `card.expiry`, `card.cvc`, `card.pin`, `card.type`) and a Wi-Fi network (`wifi.ssid`,
  `wifi.password`, `wifi.security`), which make the card's payment card and Wi-Fi views. The phone's own, until
  the Mac takes them over (`~/Projects/keepass-bar/HANDOVER-more-faces.md`): a bank account's (`bank.iban`,
  `bank.bic`, `bank.name`, `bank.holder`, `bank.account` where there is no IBAN, `bank.code` for a national bank
  code such as a БИК or a routing number, `bank.corr` for a correspondent account), an identity document's
  (`doc.type`, `doc.number`, `doc.name`, `doc.country`, `doc.birth`, `doc.issued` dates, `doc.expiry` a date
  something ends on like `until`), an SSH login's (`ssh.host`, `ssh.port`, `ssh.user`) and a crypto wallet's
  (`crypto.seed` the recovery phrase, `crypto.passphrase`, `crypto.address` a receiving address, several allowed,
  `crypto.xpub` an extended public key, `crypto.path` the derivation path). Type names ignore
  case and outer spaces; field names match exactly. A type this version does not know is kept (the editor
  too) and counts as none.
- **A field without a type** is read by its value (unmistakable dates, http(s) links, numbered codes, and an IBAN:
  the right country, length and mod-97 check, shown in groups of four with its country's flag and copied without
  spaces; the editor names it "IBAN · auto"; it makes no face without the types). The
  rules the settings entry held before (`<field name> = <type>` lines) are no longer read: the Mac's field
  report moves them into the entries. Protected fields are never read, typed or not.
- **The offer to type a card, a network, an account, a document, an SSH login or a crypto wallet** (the phone's own;
  `TypeSuggestion`): fields without a type named as a payment card's parts (a number — 12 to 19 digits when unprotected, a protected one by its name — and at least an
  expiry, a CVC or a holder; 1Password's names, English, German and Russian ones, a 1Password section left out) or a
  Wi-Fi network's (a name, and a password field, or a security field saying it is open: a network whose password is
  elsewhere is not offered, its face would call it open) make the card offer "Looks like a payment card · Show as
  card" / "Looks like a Wi-Fi network · Show as network". Likewise a bank account (an IBAN by its value, or a field
  named IBAN when protected; else a 20-digit account named as one with a Russian БИК; the BIC / SWIFT, holder, bank
  and correspondent account by their names, 1Password's among them), an identity document (a document number by
  its name — a bare "Number" only with two parts only a document has, as a short card's number and expiry would
  otherwise pass — and at least a date or a country) and an SSH login (a host by its name whose value is a host, and
  a port, a user, KeePassXC's SSH agent settings or "SSH" in the names or title: a host alone could be a
  website's), and a crypto wallet (a recovery phrase by its value when it passes its check, else by its name — "Seed",
  "Recovery phrase", "Mnemonic", "Сид-фраза"… — when protected or when most of its words are a list's; an address or
  extended public key by its checksum, one without a checksum only under an address's name; then a passphrase
  beside a phrase and a derivation path, "Looks like a crypto wallet · Show as wallet"). The tap saves the types (an edit of the field types only, §3.3), so the face appears here (and the
  Mac draws a card's and a network's view too); nothing is typed without it. "Not now" is remembered per entry (its id in the app's settings, as Recent keeps
  ids); not offered in the recycle bin, nor for fields typed otherwise.
- **The editor**: the type chip under a field's value names the type it has ("Card expiry ▾"), or what its
  value is read as ("Text · auto ▾"), or a type not known here ("“x.y” ▾"); its menu: *Automatic (…)* (no type; what it is read as in brackets), Text, Date, Date until
  (expiry), URL, Code, Password, *Card ›*, *Wi-Fi ›*, *Bank account ›*, *Document ›*, *SSH ›*, *Crypto ›*, and a type not known
  here, kept, shown disabled. A type
  given to a field without a name names it. A field typed `date`, `until` or `card.expiry` and not protected
  has a calendar (the day written `2027-10-31`, a card's expiry as its month `2027-10`); a value that is no
  date says so under it ("Not a date, shown as text…") and is saved all the same; likewise a recovery phrase that
  fails its check (read here even when protected: the form holds its text anyway), an address or extended key whose
  checksum fails, a private key typed as the extended key, a path that is none. The types saved are every
  field's that has one, but `text` only where the value would be read as something else; a rename carries
  the type along; types of fields the entry no longer has go with the next save, which they do not cause.
  A card number, CVC or PIN gets the number keyboard, an SSH port the digits.

## 7. Autofill

`AutofillService` (Android's Autofill Framework); the user picks Keething in Settings → Passwords &
autofill (Settings in the app has a button for it), and in Chrome sets Settings › Autofill services ›
"Autofill using another service" (Firefox needs nothing). The rules are in `core/autofill`, JVM-tested; the
service, the screen it opens and the system's view structure are in `app/autofill`.

- **The fields** (`FieldClassifier`, `AutofillForm`): each input field of the screen is a username, phone
  number, password, new password, one-time code, an unnamed text field, or something else (search,
  address, payment card, a checkbox, hidden or disabled). Decided by the autofill hints first (`password`,
  `emailAddress`, `smsOTPCode`, …), then the HTML `autocomplete` and `type`, then the input type, last the
  words in the view's id, its hint and the HTML names, in English, German and Russian ("Passwort
  wiederholen", "Код подтверждения"). A visible-password input type counts only with a password word (apps
  use it to keep the keyboard from suggesting words). A form has usernames (or, when none, the phone number,
  or the unnamed text field right before the first password), passwords and codes. Nothing is offered on a
  sign-up (only new passwords: it gets "Generate a password" and the save offer, below), on Keething's own
  screens, or on a screen without such fields; asking for
  autofill by hand (long press › Autofill) takes the focused unnamed field as the username. A password
  change gets only the current password. Never a field's text, only what it is.
- **The target**: in a known browser (Chrome, Firefox and their forks, Brave, Edge, Samsung Internet,
  Vivaldi, DuckDuckGo, …; `Browsers`) the web domain of the page, with its scheme; fields of another domain
  (a frame of another site) are left out. Anywhere else the app's package name, which the system gives:
  an app's web domain is never trusted, since any app can show (or claim) a bank's page in a web view.
  Android's browsers name a page by its host only (`localhost` for `http://localhost:8000`, checked in
  Chrome and Firefox), so a port written in an entry matches any port then.
- **The entries** (`AutofillMatcher`): for a page, the Mac app's site matching (`SiteMatcher`: the same
  host first, then the same registrable domain by the public suffix list; the entry's URL and its
  `KP2A_URL` fields; a protected field is never read), with one rule more: a plain `http://` page gets only
  addresses written with `http://`, so a password kept for an https site is never sent unencrypted. For an
  app: the entries that name it (`androidapp://<package>`, as Keepass2Android writes it), then the entries
  of the websites the app is verified for (Android app links: the site's `assetlinks.json` names the app's
  key and the system checked it; read offline with `DomainVerificationManager`, `verified` hosts only, a
  `*.bank.de` wildcard is a domain match). So one entry with `https://bank.de` serves the bank's app as on
  the iPhone. That needs `QUERY_ALL_PACKAGES`: the system shows an app's verified websites only to an app
  that can see it, a fill request does not make the app being filled visible (checked on the test phone),
  and the apps cannot be named in advance. The app has no network, so what it sees stays on the phone.
- **Unlocked**: up to 6 matching entries, best first (title, username), as the keyboard's chips (inline
  suggestions, Gboard) or the dropdown under the field; their username and password go into the response,
  so a tap fills at once. Then "Search Keething" ("Another entry" when entries were offered), pinned at
  the keyboard's edge as a search icon: it opens the picker. A form that asks only for a code offers only
  entries with one.
- **One-time code**: an entry with a code is offered without its values; the tap opens the autofill screen
  invisibly, which makes the current code then and fills it (with the username and password when the form
  has them). Checked: the code filled in Chrome was the one keepassxc-cli showed. Firefox does not give a
  lone code field to autofill services at all (it registers login forms only); Chrome does.
- **The code after a login**: when autofill fills the username or password of an entry
  that has a one-time code and the form has no code field, the current code goes to the clipboard as any
  copy does (marked sensitive, cleared after 30 s, §4), so the next page's code field takes a paste: in
  Firefox, in a row of one-digit boxes, in a field autofill does not recognise. Such an entry's dataset goes
  through the invisible autofill screen like a code's, so the code is made when it is tapped. The code may
  be near the end of its period; most sites also take the one before. Checked on the test phone (Chrome, the local
  login page): the username and password filled, the code pasted into another field was the one
  keepassxc-cli showed, and a paste 35 s later gave nothing.
- **Locked**: one item, "Unlock Keething" ("to fill <site or app>" in the dropdown). It opens the
  autofill screen: the lock screen (the fingerprint is offered at once), then the one matching entry is
  filled at once, or the picker shows when there are several or none. After a cancelled unlock the item
  still says "Unlock" for the rest of that screen's autofill session (the system keeps the first answer);
  it then goes straight to the picker.
- **The picker** (the autofill screen): "Choose an entry · to fill <site or app>", the search field at the
  bottom; nothing typed: "Suggested" (the matching entries), then recently used and all; typing searches
  the vault as the list does (URLs included). A tap fills the entry and closes; ✕ closes without filling.
  Choosing an entry that does not match the target is the user's explicit choice; the title says where it
  goes.
- **Trust**: the autofill screen acts only on its own immutable intent (the fields, the target, the mode,
  the entry), never on the view structure the system adds to it: the app being filled starts that intent
  and could add a structure that names another app. The screen is not exported and has `FLAG_SECURE`.
- **Never** offered: a protected custom value; an entry that matches neither the page's site nor the app.
  The log says kinds and counts only (`fill request: 2 fields, web page, 1 username, 1 password, 0 code`),
  never a value, a domain or an app.
- Not done, and why: a check of the browser's signing certificate (a fake browser would have to be
  installed under a browser's package name; Chrome is a system app); `android://<hash>@<package>` URLs
  (Chrome's way to name an app; not known to be in the owner's database); honouring an app's
  `importantForAutofill="no"` (apps set it on login fields too).
- **Save**: a form with a password gets `SaveInfo` (the password, or a sign-up's or a password change's new
  one, required; the username optional; also when all its fields go, for an app that changes screens), locked
  or not, so the system offers "Save username and password to Keething" after the form is sent with a
  password no dataset filled (a login filled from an entry and sent unchanged is not offered: the system
  compares). On Save, the service reads what was typed (`ScreenFields` with the texts, the one place it does;
  the username from an earlier screen of the same form when this one has none) and keeps it in the app's
  memory as a wipeable secret for up to 10 minutes (`PendingSaves`); the save screen's intent carries only a
  random token, so the password never goes through the system in an intent. The save screen
  (`SaveLoginActivity`, not exported, `FLAG_SECURE`) unlocks first if need be, then decides
  (`SavePlanner`, `core/autofill`): the matching entry with the username typed (any case) — or, without one,
  the one whose current password was typed in a password change, or the only matching entry — gets the new
  password: the editor of that entry opens with it, under "Update the password of “…”?" and "Save as a new
  entry instead"; it has the password already: "Already saved in Keething" and nothing written (when
  locked; unlocked the system does not ask); otherwise the new-entry editor opens prefilled: title the page's
  host without `www.` or the app's name, the username, the password, the address (`https://host`, or
  `androidapp://<package>`), the root group to start with. Saving is the editor's (§3.3, "Saving and
  checking…", then Done and a toast back in the app or browser).
- **Generate**: a new-password field (a sign-up, or a password change's new one) gets "Generate a password"
  (a wand), also locked: it opens the password generator (§6.2) over the app or page; "Use this password"
  fills every new-password field (the repetition too), and the save offer then carries it. Nothing of an
  entry is offered on a sign-up.
- Not done, and why: choosing an entry by hand for an app does not yet offer to add its `androidapp://`
  address (ROADMAP Later); a password change's "which entry" by the current password is JVM-tested only.
- Save checked on the test phone (release build, `autofill.kdbx`, the local `save-signup` / `save-login`
  pages): in Chrome a sign-up's password generated (Strong, 20 characters, both fields), saved as a new entry
  `localhost`; the same username with another password typed: "Update the password of “localhost”?", saved;
  the same login again: no offer; locked, a second username: the unlock, then a new entry. keepassxc-cli on
  the Mac read both entries with their passwords. In Firefox the generator filled both fields and the save
  offer came (declined there). Not checked: an app's login (none on the test phone without the owner's
  accounts), a password change page, a username on an earlier screen.
- Checked on the test phone (Pixel 6, Android 17; debug and release builds, a fictitious database and local
  test pages over `adb reverse`): Firefox and Chrome, locked and unlocked, chips and the dropdown, a login
  with hints and one without, username first, a sign-up (nothing offered), a frame of another site (Chrome
  leaves it out itself), the code page (Chrome), the picker with a search and a non-matching entry, ✕;
  apps by hand: Keep by `androidapp://`, Translate by its verified `translate.google.com` (an
  `accounts.google.com` entry). Not checked: a real app's login screen (none on the test phone without
  touching the owner's accounts), the dropdown in dark mode.

## 8. Build and install

- Built on the Mac from the command line (JDK 21, Android command-line tools, the Gradle wrapper); Android
  Studio is optional.
- Release builds are shrunk with R8 (about 3 MB, against 66 MB for a debug build with the whole icon set).
- Release APK signed with the owner's own keystore (kept outside the repository, password not in files;
  `tools/build-apk.sh`, DEVELOPMENT.md); installed with `adb install -r` (USB or wireless debugging) or by copying the
  APK to the phone. Until 2026-10-21 the test builds were signed with the Mac's debug key instead (a known
  password: whoever has that file can sign an update the phone accepts); the owner's phone gets the own key.
- Updates keep the same signing key (otherwise Android refuses the update and the app must be uninstalled,
  which deletes the biometric key and settings).
- minSdk 34 (only the owner's phone and an emulator), targetSdk = the newest installed (Android 17).
- Google's developer verification for side-loaded apps (rolling out 2026–2027): check its state before the
  first install; installing over `adb` is expected to stay possible.

### 8.1 Google Play (ROADMAP step 8, RELEASE.md)

Decided (2026-10-21): the app is to be published on Google Play. It needs the SAF storage (§3.1: Play does not
allow all-files access for a password manager), a privacy policy, a store listing, the licences shown in the
app, and a donation option. `QUERY_ALL_PACKAGES` (autofill's verified websites, §7) is a restricted permission
on Play: check that a password manager's autofill is an accepted use, else matching apps by their websites
goes. External donation links are a grey area under Play's payment rules; the safe ways are
a "tip" through Play Billing or a separate paid "supporter" build (as KeePassDX does) — check the rules at
that time. A donation link, if any, comes only with the Play release.

## 9. Fixtures

`fixtures/` holds fictitious databases copied from the Mac project (password `pw`): KDBX 4 with AES + AES-KDF,
AES + Argon2d, ChaCha20 + Argon2id, a KDBX 3 one (must be refused as unsupported), `kdbx4-export.xml` (keepassxc-cli's XML export of
another fictitious database: two entries with attachments; KDBX 4 exports carry no `Meta/Binaries`) and `sample.xml` (hand-made, the real structure). The core's tests use them
the way the Mac tests do (an export's `Meta/Binaries` are not read: KDBX 4 keeps attachments in the file's
inner header). Never use the real database in tests.

## 10. Sharing entries

Entries encrypted for one phone, carried any way: a messenger, mail, a picture, a phone's screen scanned in
person. Both sides run Keething. Agreed with the owner on 2026-10-23 (the mock-ups are the canvas's "Sharing"
rows); sending without a request agreed on 2026-10-24 (§10.4); contacts, a person's key kept so that no request is
needed each time, come later (ROADMAP).

### 10.1 How it works

- **A request.** The one who wants an entry asks for it (Sharing › Ask for an entry): their name and what for,
  one line each, both sent as they are ("whoever carries it can read them"). The phone makes an X25519 key pair;
  the request carries the public key, the name, the reason, when it was made and until when it is valid: 1, 3 or
  7 days (Settings; 3 to start with; never more than 7). The name is remembered for the next request and answer.
- **The pictures.** Six of the 64 emoji Matrix uses to check a key (`sas-emoji.json` of the Matrix
  specification, Apache 2.0, `assets/licenses`), each with its English name, made from the whole request:
  Argon2id (64 MiB, 3 passes, 2 lanes, salt "Keething pictures") of its SHA-256, the first 36 bits, six a
  picture. The asking phone shows them, with their names; the answering phone does not. The asker reads them out
  by a call or in person, not in the chat the code came through, and the one who answers taps them in that order
  on a grid of all 64 (six places above it, a key to take the last one back). The request is checked only when the
  six tapped are its own, in order: the core compares them, so none can be skipped or waved through (chosen by the
  owner on 2026-10-23 over a "They match" button: someone who looks at four of six leaves 24 bits, a few GPU
  hours). Six that differ: "Tap them again", or "They really differ · delete it". In person: tap what the asker's
  screen shows. A request swapped on the way (another key, another reason, another name) has other pictures;
  making one with the same six takes 2^36 tries on average, each 64 MiB of Argon2id (0.25 s a request on the
  Pixel 6; the review of 2026-10-23 estimated 400 to 800 days of a high-end GPU, a few thousand dollars of rented
  ones). The pictures are never drawn on a request's picture: an attacker would draw them too. Entries are chosen
  only once the request is checked; the core refuses an answer before that.
- **The answer.** The entries chosen (at most 50) and, of each, the parts left on: username, password, website,
  notes, each custom field, and the one-time code, off to start with ("With it, <name> can sign in without you: the
  second step is no longer yours alone"); so are custom fields KeePass keeps a code's secret in (`TimeOtp-…`,
  `HmacOtp-…`, not read as a code here), with the same warning. The title, tags, expiry and the custom fields' types always go; attachments
  ("1 attachment stays on this phone"), history and the icon never do. The sender names themselves ("From",
  remembered). Sealed with HPKE (RFC 9180, base mode: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM),
  `info` "Keething answer v1" followed by the request's whole SHA-256: it opens only for the very request it
  answers, its name, reason and validity included. Making it removes the request on the sender's phone and keeps
  its id until it expires: one answer per request, the same request read again is "Answered already". The answer
  stays on its screen until Done.
- **Receiving.** The asking phone opens it with the request's private key: "From <name>", the entries with their
  fields (protected ones masked with an eye, each copied as the card copies), and "Only this phone could open it,
  but anyone who saw your request could have made it, and the name is theirs to choose". Save makes each entry a
  new one in the group chosen (the top level to start with), each its own checked save (§3.3), a one-time code as
  KeePassXC's `otp` link (`TotpSetup`, the Mac's `TOTPSetup`), tagged `received` besides the sender's tags (once,
  in any case): what came from outside is found with `#received`, and taking the tag off says it was looked at.
  When all are saved the request is closed: its key goes and the answer can no longer be opened. A save that
  fails stops there; trying again saves the rest only.
  "Close the request without saving" closes it too, after a question.
- **Expiry.** A request goes with its key when it has expired (whenever the requests are read or changed); an
  expired request cannot be answered ("It ran out on …. Ask <name> to send a new one"). One made more than a day
  ahead of this phone's clock, or valid for longer than a week and a day from now, is refused ("dates are wrong").
- Not done, and why: who answered cannot be proven (anyone who has the request can seal an answer for it), so
  what arrives always becomes new entries, never an update of an existing one; contacts, with both sides' keys,
  will close that gap. Until a request is closed or expires, its private key on the asking phone opens the answer
  that travelled; afterwards nothing does, not even that phone with the database's password.

### 10.2 The formats (`core/sharing`)

- **Text**: `keething:`, the message in base64url without padding, and `.`. A QR code holds the same text. In
  pasted text the codes are found among other words, line breaks inside a code (an e-mail's wrapping) ignored; a
  code without its end is "cut short".
- **Request**: version 1, kind 1, created and expires (seconds, 64-bit big-endian), the public key (32 bytes),
  the name and the reason (UTF-8 after their length, unsigned LEB128, the shortest form only). The name at most
  64 characters, the reason 200, one line each, no control or format characters (a right-to-left override, a
  zero-width space). A public key of small order is refused. Its id: the first 16 bytes of its SHA-256.
- **Answer**: version 1, kind 2, the request's id, HPKE's `enc` (32 bytes), the ciphertext with its tag; the
  first three are the AEAD's associated data.
- **Content** (sealed): version 1, the sender's name, the entries (title, username, password, URL, notes, tags,
  expiry, custom fields with a protected flag, field types, the one-time code as its key, digits, period and
  algorithm), then zeros up to a multiple of 128 bytes (its length says little about a password's). Read
  strictly: a field named as KeePass's own fields or the code's, two fields of one name, a type for a field that
  is not there, a control character XML cannot hold, a date outside the years 1 to 9999, more than 50 entries, no
  entries: refused. The sender reads what it made by the same rules before sealing, so what one side sends the
  other reads. The secrets are read into wipeable buffers. Any failure reading a code is "cannot be read", never
  a crash.
- **Parts**: a message over 600 bytes (a QR code of version 23 at medium correction, which a phone camera reads
  off another phone's screen) is split: version 1, kind 3, the first 8 bytes of the whole message's SHA-256, the
  index, the count, the piece; at most 16 parts. They are read in any order, from one picture or several, also
  one picture at a time from a messenger; pieces of two messages never make one. Text carries any message whole.

### 10.3 On the phone

- **Kept**: the requests both ways and the ids answered, and the envelopes sent and the ids of those saved
  (§10.4), are one file, `noBackupFilesDir/sharing/requests` (version 2; version 1, without envelopes, is read),
  sealed with AES-256-GCM under 32 random bytes kept in `sharing/key`, wrapped by an AES key in the Android
  Keystore (`SharingKey`; no user authentication, never leaves the Keystore). Read at the unlock, in memory while
  unlocked, wiped on lock. A file that cannot be read is left as it is and nothing replaces it. Not keyed to the
  database's own key: KeePassXC gives the file a new key-derivation salt on every save (§4), and the requests
  waiting for an answer would go with each of its edits. Not in the database: KeePass keeps an entry's old versions
  in its history, so a key deleted from an entry would stay in the file and sync to the Mac. Skipped, and why:
  sealing the file with the database's password (it was the first design) would keep the keys from someone who
  runs code as the app on an unlocked phone without the password; that is outside what the app defends against
  (§4), and its cost was losing the requests to every KeePassXC save.
- **Sending**: a request's or an answer's code is shown at full brightness for a camera, sent as a picture (a PNG
  of about 1000 px, a caption under the code: "Keething · Alex asks for" and the reason, or "Keething · answer to
  “…”" and "Code 1 of 2: all are needed"; from `cacheDir/sharing` through a `FileProvider`, cleared when the app
  starts), sent as text (a few words for the person, then the code), or saved to Pictures/Keething. The share
  sheet leaves out Keething's own "Read with Keething".
- **Reading**: the back camera (CameraX, frames at about Full HD, ZXing); pictures from the system's photo
  picker, several at once (all of an answer's parts); pasted text; and "Read with Keething" in other apps' share
  sheet: a picture, several, or a text from a messenger or mail, read at once by a see-through activity of its
  own (no task of the app's, so it never opens inside the other app's), then shown once the vault is unlocked.
  Only pictures another app serves (`content:`, not the app's own providers) are read: the app reads with its own
  rights, which the sender must not borrow.
  The parts read so far are kept for the whole app (a lock clears them).
- **Screens**: Sharing from the list's header: "To answer" (the asker, the reason, when it expires, Checked or
  Check first), "Waiting for an answer" and "Sent, pictures kept" (§10.4), Send entries without a request, Read
  a code and Ask for an entry at the foot (on a very large display size "Ask", §6.1). Ask for an entry; Your
  request (the code, the pictures, Send the picture, Send as text, Save picture, delete after a question); Read a
  code (a banner says what a code could not do: cut short, not a Keething code, nothing in what was shared, own
  request, expired, dates wrong, answered already, not for a request on this phone, code 1 of 2 read); a request
  to answer (the six places and the 64 pictures to tap; Tap them again, They really differ · delete it; once
  checked, Choose entries); Choose entries (the list with boxes, searched as the list is, the recycle bin left
  out); What to send; the answer (its codes with Previous and Next); an answer received (Save into, Save, Close
  without saving; then Saved and checked with Open the entry). From a card: Send to a request (the requests to
  answer; one not checked goes to its pictures first, the entry already chosen). A screen whose request went
  closes itself.
- Logs say durations and counts only ("request made in 248 ms", "handed over: 1 texts").
- Checked on 2026-10-23: the JVM tests (RFC 9180's vectors, the pictures against OpenSSL's Argon2id, the
  formats, the requests, the parts, a one-time code written on a new entry). Apple's CryptoKit (`HPKE` with
  Curve25519 / HKDF-SHA256 / AES-GCM-256, assembled as a custom suite: CryptoKit has no ready name for it) opened
  an answer made here and sealed one this opens: `InteropVectorTest` holds both, every byte fixed, for the Mac. On the Pixel 6 (release build,
  `autofill.kdbx`) and the Android 17 emulator (debug build, `demo.kdbx`) as two phones, fictitious databases
  only: a request's picture made on one and handed to the other through "Read with Keething", the same six
  pictures on both; an answer of 2 entries (one with its one-time code) back the same way, saved and checked,
  and keepassxc-cli read the new entries with every field, protected ones included, as in the original, and the
  same current code; an answer of 11 entries in 2 codes, handed over one picture at a time, and both pictures
  picked at once; Send as text, copied from the share sheet and pasted (own request); the share sheet with 2
  pictures; Save picture; Send to a request from a card. After the review's fixes: the requests sealed with the
  Keystore-kept key, a request handed over as a `content:` picture (a `file:` path refused: "No code in what was
  shared"), an answer saved with the tag `received` (keepassxc-cli read it), the same request read again after
  answering ("Answered already"). The pictures tapped (the emulator answering the phone's request): a wrong sixth
  said so, the last one taken back and the right one tapped, then Checked and Choose entries. Not checked: the
  camera reading a code.

### 10.4 Without a request

The one who has the entries starts (a new colleague's database account, a new mail account): one message, an
"envelope", opened by ten pictures the sender gives another way than the code. Agreed with the owner on 2026-10-24.

- **Sending.** Sharing › Send entries without a request, or "Without one" in a card's Send to a request; Choose
  entries and What to send as for a request, with an optional **For** (a name kept on this phone only: the list and
  the warnings say it) besides From. "Seal with ten pictures": ten drawn at random from the 64 (60 bits).
- **Sealing** (`Envelopes`): Argon2id of the ten indices (a byte each) with a 16-byte random salt, the pictures'
  parameters (64 MiB, 3 passes, 2 lanes); then HKDF-SHA256 (no salt, `info` "Keething envelope v1") gives the
  AES-256-GCM key and the 12-byte nonce, the salt making both new each time. The content is an answer's (§10.2).
  About 0.3 s to seal or to try pictures (0.22 to 0.24 s on the Pixel 6).
- **Format**: version 1, kind 4, created and expires (seconds), the salt, then the ciphertext with its tag; all
  before the ciphertext is the associated data. Its id: the first 16 bytes of its SHA-256. Parts, text and QR codes
  as for an answer. `InteropVectorTest` holds one, every byte fixed, made outside this code (OpenSSL's Argon2id and
  HKDF, CryptoKit's HKDF and AES-GCM), for the Mac.
- **The sender's screen**: the code or the ten pictures, never both at once (one photo of the screen would hold
  both), switched by "The code" / "The pictures" above them; the code first. The code as an answer's (Send the
  code, Send as text, Save picture; "In person: let them scan this screen"; the screen at full brightness). The
  pictures numbered 1 to 10 with their names, Send the pictures and Copy (the clipboard marked sensitive, cleared
  after 30 s), with "They open the code: give them another way than the code. Read them out by a call, or send them
  through another app. Whoever has both opens the entries." The code's text never holds the pictures, nor the pictures' text the code. The share
  sheet tells the app which app took the code or the pictures (`EXTRA_CHOSEN_COMPONENT`, `ChosenAppReceiver`, not
  exported), and the other one's share sheet leaves that app out ("Drive is left out of the share sheet: the code
  went that way"); Copy can still put both in one chat, the sender's choice. Kept, with the code, the pictures, For,
  From and the titles, until it expires or "Forget the pictures" (after a question: "Until they are given, nobody can
  open the code then").
- **The recipient**: Read a code as always; then "Entries sent to you": ten places over the grid of 64, and Paste the
  pictures, which finds ten in a row in what was copied, by emoji (with or without U+FE0F) or name, in any case, an
  emoji with its own name beside it counting once, numbers and punctuation between them allowed, other words
  breaking a run. Wrong ones: "These pictures do not open it" (a wrong picture and a changed code cannot be told
  apart), Take the last back or Tap them again; tries are not limited, as anyone with the code can try it
  elsewhere. Opened: the Received screen with "Only these pictures open it, so it comes from whoever gave you them.
  Anyone who had both the code and the pictures could open it too."; saved as an answer's entries (new entries,
  tagged `received`), then its id is kept until it expires ("Saved already"); Not now leaves it to open again. The
  pictures typed stay in the screen's model, never in saved state. Read on the phone that sent it: "These are your
  own"; expired, or dates wrong: said as for a request.
- Logs: "envelope made in 467 ms", "envelope tried in 222 ms", "code handed to an app".
- Not done, and why: the expiry is real only until the pictures are given (the sender's phone forgets them);
  afterwards Keething refuses an expired envelope, but the code and the pictures open it for good in any program
  that ignores the date. No key can be thrown away as with a request: for an account, a password changed at the first
  sign-in is the remedy. Whoever sees both channels opens it; the app keeps them apart but cannot stop a copy. The
  attack on the code alone: 2^59 tries on average, each 64 MiB of Argon2id; by the review's figure for the request's
  pictures (400 to 800 GPU-days for 2^36), 3 to 7 billion GPU-days.
- Checked on 2026-10-24: the JVM tests (the vector, wrong and changed envelopes, dates, own, saved, the file's
  versions, pasted pictures). The Android 17 emulator (debug build, `demo.kdbx`) sending to the Pixel 6 (release
  build, `autofill.kdbx`), fictitious databases only: an envelope of 2 entries handed over as its picture through
  "Read with Keething", a wrong tenth picture refused, the right one opened it, saved and read by keepassxc-cli with
  every field and the tag; read again, "Saved already"; one of 1 entry handed over as text. On the emulator: the code
  sent to Drive, then Drive missing from the pictures' share sheet with the line saying so; own envelope; Copy, Forget
  the pictures, then the same code opened there with Paste the pictures; Without one from a card; the Sharing screen
  at font scale 1.3 and density 500. Not checked: the camera reading an envelope, a call, an envelope of several
  codes on a phone.
