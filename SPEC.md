# Keething for Android — spec

A reference to a *ROADMAP* step, *RELEASE.md* or the Mac's plans says where and when a decision was taken: those
working files moved to `~/Projects/keething-spec` on 2026-10-27 (`plans/README.md` maps the names), with what
comes next (its features, ideas and open stages).

The application id is `dev.keething.android`, the packages `dev.keething.*`. The Mac app's settings entry is
the one titled "Keething", the field types are read under `Keething.FieldTypes` (§6.3) and nothing else: a
database from before 2026-10-21 has the apps' former name there, which the Mac still reads; rename the entry by
hand and the field types' key with `tools/rename-field-types.sh` (DEVELOPMENT.md).

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
- Open databases from the Syncthing folder, several side by side (§3.1); notice new versions; never lose track of
  a file.
- Unlock with the master password, then with the fingerprint (§5).
- Search (as the Mac app: terms, fuzzy, `@group`, `#tag`, `+attachment`, `!database`, `-` for the recycle bin;
  narrowing from the last word typed).
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
reports (but Groups & tags), restoring a version from the history, YubiKey, Twofish, deleting entries for good / emptying the recycle bin, adding / removing / renaming
attachments, reordering custom fields, a Quick Settings tile, a one-time code pinned to the notification
shade (dropped: the list's code button and the code copied after autofill do that job, and it would need a
foreground service for little gain), an entry pinned to the notification shade with Copy username / password /
code (dropped 2026-10-22: doable without a service, but it saves only a switch back to the app, whose card
stays open until the lock, autofill covers most logins, and it needs the notification permission; worth it
only for frequent copying into apps autofill does not reach), Google Play (§8.1).

Planned beyond the first releases (decided by the owner on 2026-10-27; keething-spec `OPEN.md`, `plans/`): several
databases side by side (in: the list, one list over several open, the chooser and one fingerprint for several,
§3.1, §5), key files (in, §4), creating a database (in,
§3.5, §6.2), KDBX
3.1 (refused until then), merging conflicting copies (detected and shown only until then, §3.4). Groups (decided by
the owner on 2026-10-31; keething-spec `plans/android-groups.md`): an entry's group changed in the editor and from its
card (a move), new groups made from the editor's group picker, groups renamed, made, moved and deleted when empty from
Groups & tags, and Groups & tags over every open database, a block each; renaming tags and the recycle bin's groups
stay with the Mac.

## 2. Architecture

Kotlin, Jetpack Compose, Gradle (Kotlin DSL) with the wrapper checked in. A grown-up app built on best
practice: SOLID and DDD are the orientation, without fanaticism.

Gradle modules carry the architecture: each module is a feature or the shared kernel, and the allowed
dependency directions are the module dependencies, so the compiler enforces them.

```
core/domain   shared kernel, small on purpose: Entry, Vault, CustomField, Attachment, TOTPConfig,
              EntryChanges, VaultEdit, the secret value type, the app's one text folding
              (TextFolding: search, field names, a group path's levels), and the ports (DatabaseStore §3.1,
              DatabaseBackups §3.3, Clock, BiometricPasswordStores §5, SettingsStore, …).
              Depends on nothing.
core/totp     one-time codes: otpauth links, KeePassXC's and KeePass's formats, code generation → domain
core/search   search rules, ranking, site matching with the public suffix list (a resource,
              `tools/update-public-suffix-list.sh`)                                       → domain
core/kdbx     the KDBX 4 file format: crypto, reader, writer, XML tree, KeePass's schema   → domain
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

**The list of databases** (keething-spec databases.list; `core/vault` `DatabaseList`, the Mac's): the databases this
phone knows, each remembered by its **path** (standardized: `.` and `..` resolved, so the same file however written is
the same database) with an **id** of its own given when it joins the list (a UUID, never the file's: a KDBX header has
no stable id and a copy keeps every UUID), its key file's path, and choices; nothing secret. Kept as JSON under the
setting `databases`: `{"current": <id>, "databases": [record…], "version": 1}` (a record: `id`, `path`, `keyFilePath`,
`name`, `color`, `useBiometric`, `opensWithoutPassword`, `lastOpened` in seconds since 1970, `inWindow`,
`checkedInChooser`; written with sorted keys). A record without `id` or `path`, a field of another type or text that is
not JSON makes the list **unreadable**: it is kept under `databases.unreadable.<seconds>`, never overwritten, and the
list starts empty; any other field missing reads as its default, unknown ones are ignored. The first database added
is current: the one the lock screen shows. **The window** (`inWindow`, kept with the list) is the databases side by
side, each with a session of its own (§4); the current one is always in it. **Opening or choosing another database
closes nothing** (keething-spec databases.list AC4): it joins the window and becomes current, the databases already
open stay open, and its lock screen shows with Back to them (the lock screen's sheet, Open a database…, New database,
a chip of the line of those not open, ⋮ › Databases). **Close** (keething-spec databases.several-open AC4,
databases.not-open AC4) takes a database out of the window, locked, still in the list with its stored password (Recent
in ⋮ › Databases); the window keeps one, so its last is not closed ("The last one stays"); the current one closed, the
window's first open one becomes current, so the vault stays; Undo puts it back, locked (closing the only open one,
the chooser shows, or the window's last one's lock screen, and there is no Undo: it is under Recent). **Lock** one locks it alone,
the others staying open.

**The databases of the window not open** (keething-spec databases.not-open; the Mac's D6b) are one line of chips
pinned under the list's header while some are open: "Not open", "Not searched" while a search is typed, the count past
two, scrolling sideways. Each chip says its database (dot, label) and its state, the first that applies
(`NotOpenDatabases.state`, the Mac's order): Opening… while it opens; Not found, Needs its key file (by the files as they
are now, so an old failure goes once the file is back); what typing cannot fix (its key file cannot be used, Cannot be
read); the fingerprint's verdict (Fingerprint to set up again: the fingerprints changed, or its stored password was not
accepted; kept per database until one is stored again, `BiometricState.setUpAgain`); Skipped (by the chooser's run, below);
Wrong password; Password changed; Fingerprint paused (its password due) or Fingerprint to set up (nothing stored yet);
Locked. The states follow the sessions and the fingerprint at once, and the files whenever those change or the list
comes back. A tap shows its lock screen with Back, the search kept for when it opens (its entries join the list, its
chip goes); Lock all clears it as ever. A long press opens its sheet: its whole say ("Locked · tap to type its
password", "Not found at Sync/Crypto.kdbx", …), Unlock… (not while not found or unreadable), Locate… (not found),
Choose key file… (it has one), Close.

**The chooser and its run** (keething-spec databases.chooser; the Mac's D6b, D7; plan AD13). With nothing open the app
shows the lone database's lock screen, or with several in the window the **chooser**, "Open your databases": each
database of the window with how it opens (Fingerprint, Password, Password + work.keyx, Key file work.keyx, Fingerprint
paused · 14 days, Fingerprint to set up, Fingerprint to set up again; one open or opening says Open or Opening…, unchecked; `ChooserRows`); what typing cannot open (not
found, its key file not there or unusable, cannot be read) has no check and says why, with Locate… for one not found;
the others are checked as at the last unlock (`checkedInChooser`, kept at once). Past five rows the list scrolls (four
and a half show, "9 databases · scroll for the rest"). **Unlock N databases** starts the **run** (`UnlockRun`,
`CurrentDatabase.run`): the checked ones asked one after another, each on its own lock screen with "Unlocking 2 of 3"
over it (a segment per database, what opened, Stop), its line, its key file and Use fingerprint next time, no
fingerprint button, and Skip; a password goes to that database only (its typed path: stored for the fingerprint when
that is on for it); a wrong one stays to be typed again ("Wrong password — or this database needs a key file. Type it
again, or skip it."); one typing cannot open (not found, its key file not there, closed meanwhile — looked at when the
run reaches it — or a try that fails so) is skipped by itself, and one opened meanwhile counts as opened. At the end
"2 of 3 open · Family Shared: skipped · Crypto: not found" over the list, which shows on the first one opened, or over
the chooser when none opened; the skipped ones say Skipped on their chips until they do something else or another run
starts. A lock ends the run with nothing said, also one with nothing open yet (the app away past its timeout), and
nothing typed after it opens anything; Stop (and Back) ends it where it is. **Unlock with the fingerprint** (while it
would open one) and, once in a visit that began locked, the chooser by itself ask one fingerprint for every database
that can (§5): the list then shows on the first one opened; when it opens none the chooser says why ("The fingerprint
was cancelled.", "The fingerprint failed: ….", "The fingerprints on this phone changed: type the passwords to set up the
fingerprint again.", "Could not read the stored passwords: …."), and only there. **Open other…** and **New database…**
as on the lock screen: a lock screen asked for with nothing open (there, or from a lock screen's sheet) shows in place
of the chooser with Back to it, and a lock forgets it; a new database opening with nothing else open shows its own lock
screen ("Opening …"). Lock all leads to the chooser, with no fingerprint by itself.
There is **no default path and no migration**: the single path of before
(`databasePath`) is left as it was and read no more, so the first start after the list shows the welcome (§6.2). A
database is added by opening its file (the system picker, any file; the app keeps the document's path, which the
phone's storage provider and the downloads provider's `raw:` ids give, and says when a place has none); Locate… gives
a database whose file moved its new path (it keeps its id, its backups and its stored password; one open locks first;
a path another database of the list has is refused: "That file is already in the list as “Personal”."); Remove from
List forgets it with its stored password and leaves the file; the current one removed, the window's first left is
current (one not in the window stays out), else the list's first.

**One list over the open databases** (keething-spec databases.one-list; the Mac's D6): an entry is its database's id
and its own id together, its **ref** `<database id>:<entry id>` (`:` is in neither a KeePass UUID nor a database id),
so the same UUID in two databases (a copy of a file keeps every UUID) is two rows, two cards and two places in Recent.
The list works on copies whose id is the ref (one search ranks them all); the card, the editor, the history, a password
full screen and a move to the bin or out of it act on the entry in its own database, through its session, saved into
its file; a row's Delete follows its own database's recycle-bin setting. `-` lists every database's bin, the
databases in the window's order, each with its latest deleted first (a file keeps no time of the move). **Recent**
and the declined type offers (§6.3) are kept by ref: 8 are listed in all and 8 kept per database, so a locked
database's entries are not pushed out by those used meanwhile; its entries are hidden while it is locked and come
back when it opens; an entry gone from its open database is dropped, a database that left the list takes its refs
with it, and an id stored before several databases becomes the ref of the first open database holding it, else it is
dropped (no migration step). While **two or more are open** each row says its database ("● Work › Engineering", the
root group "● Work") and the card's chips start with its database's (§6.2); with one open nothing of this shows.
**A new entry** (keething-spec databases.one-list AC4–AC6) goes into the database the search names (`!Work`), else the
open card's beside the list (two panes), else the one the last new entry went into (kept across starts), else the
window's first open one, among those with a group; while two or more are open the editor's **Save into** chooses among
the open ones (those of the window not open are named under them, not offered), and another choice moves the draft:
the group goes to that database's top level, the tags offered are its, and the username becomes its default unless it
was changed. A new entry's draft goes when its own database locks; another database locking leaves it (a save in
flight keeps its sheet). Autofill fills and saves from every open database, and received entries choose theirs too
(§7, §10). Groups & tags stays with the current database; sending reads entries from any open one. The search's **`!` filter** keeps one database's entries (§6.2): the index has each entry's
database label, also with one open (`!Personal` typed by hand still filters), none for autofill's single vault.

**A database's name and colour** (keething-spec databases.name-colour; the Mac's D7b): its name is its file's
`Meta/DatabaseName` (outer spaces aside), else the file name without `.kdbx`; KeePassXC's default names "Passwords",
"Passwörter" and "Пароли" (case and accents aside) are no name. The name and `Meta/Color` (`#RRGGBB`, or the six hex
digits without `#`, kept upper-cased; anything else is none) are copied into the database's record at **every open and
reload**, so the lock screen, the sheet and Settings say them while it is locked. Its **label** is the name; when two
databases of the list have the same name (folded as the search folds: case and accents aside) each adds its file name
("Personal · Personal-2024.kdbx"), and with file names alike too its folder ("Personal · Sync/KeePass"); compared over
the whole list, so a label does not change when another database opens. A long label is cut in its middle, never at
its end. Its **colour** is one of nine (teal, orange, green, purple, pink, indigo, brown, red, grey; a dot and a text
colour each, light and dark, the Mac's): a file's own colour shown as the nearest of them in OKLab (a colour with chroma
under 0.035 is grey; otherwise the distance to both dots of each, the chroma capped at 0.12 and the lightness at half
weight: yellow becomes orange, navy indigo, magenta pink), two files of one colour both keeping it; databases without one
take the colours no database has, in the list's order, then the colour of their place (index mod 9), so a file's
colour read for the first time can move the others'. The colour and label are not written to the file (that is
keething-spec databases.rename-recolour).

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
- An entry's one-time code (keething-spec `kdbx.otp-storage`, the Mac's `TOTPFormat`) is read from one format, the
  first there: a non-blank `otp` (KeePassXC's `otpauth://` link; one that does not parse is no code, with no
  fallback), else `TOTP Seed` with `TOTP Settings` (`<period>;<digits>`), else KeePass 2.x's own `TimeOtp-*` fields
  as KeePass 2.47+ reads them (`TotpParser.parseKeePass`; its source, `EntryUtil.GetOtpSecret` and `HmacOtp`): the
  secret the first non-blank of `TimeOtp-Secret` (UTF-8), `-Secret-Hex`, `-Secret-Base32`, `-Secret-Base64` (padded,
  as .NET reads it), one that does not decode no code; `TimeOtp-Length` 6 and `TimeOtp-Period` 30 when missing, not
  a number or 0, more than 8 digits no code; `TimeOtp-Algorithm` SHA-1 when missing, else exactly `HMAC-SHA-1`,
  `HMAC-SHA-256` or `HMAC-SHA-512`. Those ten fields are never custom fields; `HmacOtp-*` (counter-based codes, not
  made here) are.
- Histories are skipped: an unlock and a reload read no entry's `History`. An entry's history is read when
  its History screen opens (§6.2): the file once more with the in-memory key (no key derivation), keeping
  that entry's `History` items only (`KdbxReader.open(…, historyOf)`), every other secret of that read
  wiped at once and the attachments' bytes too; as the Mac does it (Mac SPEC §3.22), but streamed, so it
  costs the memory of a reload, not that of the save's XML tree.

### 3.3 Writing

What the phone writes (`VaultEdit`): an entry's fields (title, username, password, URL, notes, custom fields,
their types, tags, expiry), a new entry at the end of a group's entries, an entry into the recycle bin and back out of it.
Deleting for good, history, attachments, one-time codes (but that of an entry received, §10), renaming tags and the
recycle bin's groups are left to the Mac (§1). The groups' edits are built in the save (`VaultEdit.EntryEdit` and
`VaultEdit.GroupEdit`: an entry moved, with an edit or alone, new groups made for a new or moved entry, a group renamed,
made, moved, deleted when empty; keething-spec `plans/android-groups.md` AG1, AG2); no screen makes them yet (AG4,
AG6).

A one-time code written (`EntryEditing`, the Mac's alike) first removes every code field of the entry (the ten of
§3.2), so one entry never holds two formats; a save that does not change the code leaves its fields as they are. The
code takes the entry's own format; for an entry without one, the format most of the database's current entries use
(not their histories), and `otp` when there are none or a tie: `otp` as KeePassXC 2.7 writes it (protected,
`TotpSetup`); `TOTP Seed` (protected, Base32) with `TOTP Settings`; KeePass's as `TimeOtp-Secret-Base32` (protected,
unpadded), `TimeOtp-Length`, `TimeOtp-Period` and `TimeOtp-Algorithm`, all four. A code its format cannot hold
(KeePass: more than 8 digits; `TOTP Settings`: SHA-256 or SHA-512) is written as `otp`. The fields go where the
entry's first code field was, else after its other fields; the save's check reads the code back from whichever.

The Mac app's save path, unchanged in substance (Mac SPEC §3.16 "How"; `core/vault` `VaultSaver`, `core/kdbx`
`EditableDatabase`):
1. Read the file again; open it as an XML tree that keeps everything (unknown elements, history, custom
   data, other apps' data) and writes it back in order. A protected value goes into the tree as a secret,
   decrypted straight into it: the tree holds no password in the clear either (§4).
2. Find the entry by UUID; if its `LastModificationTime` differs from the one loaded → "changed elsewhere",
   nothing is saved, the vault reloads. Other entries changed elsewhere are kept. A group's edit has no such time:
   it meets the groups as the file has them now, and is refused when they no longer allow it — the group gone
   (`GroupNotFound`), a name taken there by now, case and accents aside (`GroupNameTaken`), a group to delete not
   empty any more (`GroupNotEmpty`), a group that may not be changed so (`GroupNotChangeable`: the root, the bin or in
   it, a move into the bin, into itself or below it, or where it is), an empty name (`InvalidName`); nothing is saved
   and the vault reloads. New levels for an entry meet the file the same way: a level another app made meanwhile is
   that group (`GroupLifecycle.resolving`).
3. Apply the change as KeePassXC does: the old version into `History`, trimmed to `HistoryMaxItems`; a new
   entry with KeePassXC's elements and its group's own icon; the recycle bin made as KeePassXC makes it when
   there is none; an entry moved remembers its group in `PreviousParentGroup`, before `Times` as KeePass's schema
   has it (after `Tags` or whichever of `UUID`, `IconID`, `CustomIconUUID`, `ForegroundColor`, `BackgroundColor`,
   `OverrideURL`, `QualityCheck` the entry has; new `Tags` and a missing `Times` go where the schema has them too);
   a restored entry into the group it came from, the root group when that is gone, and an entry recycled with
   its group into that group's place, the group made again there by name (as on the Mac). An entry moved as
   KeePassXC's `Entry::setGroup` (no history snapshot of its own; an edit and a move make one), the group it left and
   each parent it leaves empty deleted for good (never the root, the bin or in it, the templates group, the group it
   goes into or above it); new groups as KeePassXC writes one, after the parent's groups; a group renamed as
   `Group::setName`, moved as `Group::setParent`; a group deleted gets a `DeletedObject` (`DeletedObjects` made after
   the root group when the file has none).
4. Write: same format, new master seed / IV / inner stream key; the KDF parameters and salt kept, so the key
   in memory writes it and a save derives nothing; attachments nothing refers to dropped, identical ones
   stored once, `Ref`s renumbered, every reference still meaning the same bytes. A `PreviousParentGroup` out of
   the schema's order (Keething put an entry's after `Times` until 2026-10, the Mac and this app alike) is moved
   to its place by any save, in histories too. The version: raised from 4.0 to 4.1 only when the document holds
   what only 4.1 has (keepass.info/help/kb/kdbx_4.1.html): a `PreviousParentGroup` (KeePassXC raises the version
   for it too; the schema lists it as 4.1, though KeePass 2.48+ does not raise for it alone), an entry's
   `QualityCheck`, a group's `Tags`, a custom icon's `Name` or `LastModificationTime`, a `CustomData` item's
   `LastModificationTime`; empty ones and a null UUID count as none. Of these the app itself writes only
   `PreviousParentGroup` (the recycle bin). Never lowered; asked nothing, the 4.0 file stays in the backup.
   KeePass reads 4.1 from 2.48 (May 2021).
5. **Check before replacing**: nothing KeePass's XML schema does not allow may be added (below; a violation
   the file already had is kept and no reason to refuse); open the new bytes with the built-in reader; every
   other entry and group exactly as before, the changed entry exactly as asked, every attachment byte for byte.
   "Before" is the reader's own reading of the same decrypted XML the tree was made from, so a fault in the tree
   shows as a difference. With groups (`SaveCheck`, the Mac's `expectedStructure`): an entry's new groups are built
   from the names asked (trimmed, each inside the one before, where the file had the levels before them), not taken
   from the file; the groups a move deleted must be the group it left, then each parent in turn, none the root, the
   group it goes into or one holding another entry; a group's edit leaves the groups as expected (a renamed or moved
   group and everything below it at its new path, a new one in its parent, the deleted one gone) and every entry as
   before but for its path; the groups gone are those the edit reports. The levels the file had are walked by name
   from the group asked (case and accents aside, as `resolving`), and no group is made, renamed or moved beside one
   of its name. Groups are matched by id and each group's parent is
   checked, found by the file's order (a group's groups follow it), so two groups of one name in one parent are each
   themselves (the Mac goes by path and does not offer such a group). The recycle bin's own groups are checked too:
   as before for an entry's edit (but the bin a recycled entry makes, in the root group), and a group's edit takes them along when the
   bin is inside the group changed, its entries' paths with them.
6. If the file on disk changed meanwhile (byte for byte), do it all again on the new file (once more, then
   fail).
7. Back up the old file into app-private storage (`filesDir/backups/<database id>/`, `<name> <local date and
   time>.kdbx`, `_2`… for more within a second, numbered after the last one there; the newest 10 in the database's
   folder, whatever the file was called, other files left alone, so two `Personal.kdbx` keep theirs apart and a
   database located under another name keeps its older ones; the flat files of before the list stay in
   `filesDir/backups` as they were; not in the Syncthing folder, and the app allows no Android backup, so they
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

**KeePass's XML schema** (keething-spec `kdbx.keepass-schema`; Mac SPEC §3.16). A file Keething writes must open
in the official KeePass 2.x, not only in KeePassXC, which is more lenient; the hashes and HMACs say nothing about
the XML inside. KeePass publishes the XML's schema, `KDBX_XML.xsd` ("KDBX 4.1 XML Schema",
keepass.info/help/download/KDBX_XML.xsd, linked from keepass.info/help/kb/kdbx.html, which gives no other check).
`KdbxSchema` (core/kdbx) is that schema written out in Kotlin, the same checker as the Mac's `KDBXSchema`: what
each element may hold and in what order (`TEntry`, `TGroup` and the others are sequences, `TMeta`, `TTimes` sets),
attributes, value formats (`True`/`False` and the group's `True`/`False`/`Null` in either case, 16-byte base64
UUIDs, base64 or ISO times, `#RRGGBB` upper case or empty, numbers in range), UUIDs used once (`KUuid`: custom
icons, groups and entries, not the versions in a history) and custom icons that exist (`RCustomIcon`). A violation
has a place (`entry <UUID>` — its history versions count as the entry, so a version copied into the history adds
nothing —, `group <UUID>`, `Meta`, `Root`, `UUID <UUID>`) and a problem, never a value. `EditableDatabase` checks
the whole document as read and again right before writing; a violation it did not have refuses the save
(`SaveFailure.BreaksFormat`: "The change would leave the file in a form KeePass does not accept (an entry's
CustomIconUUID: no such custom icon), so the database was not replaced."). A pass takes about 0.06–0.12 s on the
Mac's JVM for 56–70 MiB of XML (the fictitious demo, 1,896 entries, given 8–10 versions of 30 more fields each),
0.02 s for the demo as it is. On the test phone (Pixel 6, release build, 2026-10-29) a save of that 56 MiB file
(into the bin, and back with Undo) took 9.3–9.5 s against 8.9–9.1 s before: both passes cost about 0.4 s there. A
save on the phone in `autofill.kdbx` (two entries with an older Keething's `PreviousParentGroup` after `Times`)
wrote a file valid by `KDBX_XML.xsd` and by `KdbxSchema` (pulled with adb and checked on the Mac). The full schema
is not validated in the app: users' files hold what it does not
know (plugins, older apps), and Android has no XSD validator. As on the Mac, the checker is as strict as libxml2
2.9: no blanks around numbers, digits only for the unsigned ones, no blank inside base64's padding — where the
JDK's validator is more lenient.

**The tests hold every file to the schema itself**: `fixtures/KDBX_XML.xsd` is the official file as published
(its source, date and SHA-256 beside it in `KDBX_XML.source.txt`); the JDK's validator, like libxml2, refuses its
colour pattern's .NET escape `\u0023`, so the tests compile a copy with `#` in its place, and updating the schema
stays a plain download. Every document any test in core/kdbx and core/vault writes is validated against it by the
JDK (`javax.xml.validation`) and checked by `KdbxSchema` (core/kdbx's test fixtures: `SchemaCheck`, a JUnit
extension found through its service file; the writer tells it of every document through `WrittenDocuments`,
set by the tests only): a file the schema refuses fails the test that wrote it (unless it writes one on purpose,
`SchemaCheck.allowViolations`), and the two must agree on every file, valid or not — so the app's check misses
nothing the schema catches. One gap of the JDK's validator: it misses duplicate entry UUIDs (the `.//Group/Entry`
part of `KUuid`'s selector; libxml2 finds them, groups and icons it finds too); `KdbxSchema` finds them.
`tools/kdbx-probe.sh` does the same for a copy of a real database: the copy, the file written back and every file
its saves write (the validator's messages quote values: only their line and code are printed). On copies of the
test folder's fictitious databases it found what an older Keething wrote — `demo-kdf.kdbx` with one entry's
`PreviousParentGroup` after `Times`, `autofill.kdbx` with two —, and the file written back was valid by both.
**Not checked**: KeePass 2.x itself opening the files (KPScript under Mono, before a release; keething-spec
`kdbx.keepass-schema` AC6).

The session saves one change at a time with the key in memory (`VaultSession.save`); the vault is then the
written file as read back, and the app's own write is not read again; the outcome names the groups the save deleted
(`SaveOutcome.Saved`, as they were, innermost first: what the file lost, not a guess beforehand) for the snackbar
"Saved and checked. The empty group “Work › Old” was deleted." (two or more: "The empty groups “…” and “…” were
deleted."; shown from AG4). Refused because the entry changed or went elsewhere, or the file's groups refuse the
change (a group gone, also a new entry's, a name taken, a group not empty or not to be changed so), the vault is read
again; refused because the password changed elsewhere, that database locks (the others stay open, §4). Reloads and
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

- A `*.sync-conflict-*` copy of a database next to it is shown as a banner of that database (named while two or
  more are open; "Syncthing found two versions:
  …") with its date. v1 does not merge: it says which one is open and that the other should be merged in
  KeePassXC (Database → Merge). Later: merge in the app.

### 3.5 New database files

The core of making a database (keething-spec `kdbx.new-file`, `kdbx.new-key-file`, `kdbx.kdf-calibration`; the Mac's
SPEC §3.26). The New database screen chooses it (§6.2; keething-spec `plans/done/android-databases.md` AD7).
- **The file** (`core/kdbx` `NewDatabase`): an ordinary KeePass file. KDBX 4.0, gzip, AES-256 or ChaCha20 (Twofish never
  written), Argon2d, Argon2id or AES-KDF with a fresh 32-byte salt (AES-KDF: seed) from `SecureRandom`; the KDF
  parameters as KeePass writes them (`$UUID`; Argon2 `V` 0x13, `S`, `P`, `M` in bytes, `I`; AES-KDF under KeePass's own
  id `c9d9f39a…` with `R` and `S`), no public custom data. Parameters KeePass 2.x would not run are refused before
  anything is measured (iterations 1 to 2³²−1, lanes 1 to 2²⁴−1, memory in whole KiB, at least 8 KiB a lane, under
  2 GiB; AES-KDF at least 1 round; the phone's own Argon2 also stops at 2³¹−1 iterations). The XML as keepassxc-cli
  2.7.12's `db-create` writes it, as KDBX 4: Generator `Keething`, the name given (outer blanks and control characters
  dropped, or empty), `MemoryProtection` the password only, the bin enabled but not made (the first delete makes it,
  §3.3), `HistoryMaxItems` 10, `HistoryMaxSize` 6 MiB, no KeePassXC custom data; one root group named as the database,
  else the file name without `.kdbx`, else "Root"; no entries. Checked against KeePass's schema before it is written.
- **The creator** (`core/vault` `DatabaseCreator`): nothing at the path and its folder there, said first; the file made
  in memory with the key derived once (`DerivedKey`, kept for the first unlock), read back by the app's reader (the
  name, one root group of that name, no entries, no attachments, the format asked for) and only then put in place.
  Failures: there is already a file (`Exists`), the folder is not there, not written (with the reason), did not check
  out, not supported; nothing is left behind. A **new key file**: 32 random bytes as XML 2.0, byte for byte as
  KeePassXC writes a `.keyx`, never over a file there, read back before its key is used (removed again when it does not
  read back).
- **Putting it in place** (`app` `PathNewFile`; the owner's choice of 2026-10-29): created with `O_EXCL` and written in
  one go, synced. A database is never replaced, not even by a file a sync put there meanwhile; never a check followed
  by a move. Measured on the Pixel 6 in the Syncthing folder (shared storage, FUSE): a hard link (`link(2)`, the Mac's
  fallback) is refused with `EACCES`, so is a symbolic link (a dangling link cannot be there); `O_EXCL` refuses a file
  already there; a new file keeps its modification time through reads (§3.3 step 7 is for a rename over a file, not
  needed here). The file is empty for the milliseconds between its creation and its one write (about 1.2 KB), against
  Syncthing's 10 s watcher delay; checked with the Pixel 6 connected: a database, a key file and a 4 KB file made this
  way reached the Mac byte for byte, each synced once at its full size (`Synced file` in the Mac's log), and
  keepassxc-cli opened the database there with the key file. Owner-only (0600, a key file 0400) where the storage has permissions (app-private
  storage); shared storage has none to set.
- **The measurement** (`core/kdbx` `KdfCalibration`, `core/vault` `EncryptionMeasurement`): as the Mac's, the KDF run
  with the memory and lanes chosen and the iterations scaled to the time asked for; the fixed cost of a run believed
  only under half a run, so it errs towards a file that opens sooner; at least 2 iterations or 100,000 rounds; a clock
  that does not move is an error; cancellable between runs. The phone's default (the owner's choice of 2026-10-29):
  AES-256, Argon2d, 64 MiB, min(cores, 4) lanes, **1 s**. Measured on the Pixel 6 (release build, 4 lanes): 1 s gave
  11–24 iterations in six runs (the plain estimate when Argon2's 64 MiB allocation looked like more than half a run),
  files that open in 0.50–1.04 s, the measuring itself 0.84–0.98 s; 0.5 s gave 5 iterations (0.22–0.29 s). The Mac's
  1 s default (`created-aes-argon2d.kdbx`, 115 iterations) takes 4.8–5.0 s here, about five times, not the two to
  three the Mac's page says (the owner's database has 2 lanes); a phone-made 1 s file (24 iterations) opens in
  keepassxc-cli on the Mac in 0.22 s.
- **Create** (`app` `NewDatabaseModel`, the Mac's `NewDatabaseModel`; keething-spec `databases.new-database`,
  `databases.new-database-create`): measured on this phone unless the Encryption page measured these choices already,
  then a new key file (made first, removed again when the database is not made; one the user has is only read), then
  the database. Measuring can be cancelled; from the writing on nothing stops it. It runs in the app's scope, not the
  screen's, and **a short foreground service** (`CreatingService`, Android 14's `shortService`, no permission of its
  own; its notification is not shown without the notification permission, which is not asked) keeps the process while
  it runs: removing the app from recents would otherwise end the process in the middle (the owner's choice at AD7's
  start; to be looked into: `.x/RESEARCH-ad7-app-ended-while-creating.md`, not versioned). What a process that ended
  anyway left (a crash, the system's own kill) is cleaned up at the next start: **`CreationNote`** (`filesDir/creating.note`)
  names each file before it is written, with its SHA-256 and size; a file it names that is still empty or byte for byte
  as written is removed, any other is someone else's and stays, and the app says "“Family Shared” was not created" ("Keething
  was closed while writing it, so nothing of it was kept. Create it again."). A Create whose files the list already
  names (the process ended between the record joining the list and the note's end) was made: its files stay, nothing
  is said.
- **After Create** (`CurrentDatabase.created`): a record of the list at that path (its file gone, even the current one,
  which closes) is another database and goes first with its stored password, its days and its key-file note; the new
  one joins the list with its key file and its Use fingerprint, becomes current and opens with the very key it was made
  with (`VaultSession.unlockAdopting`: the derived key is in it, nothing is derived again, no key file is read), as a
  typed unlock of the password (`BiometricUnlock.unlockCreated`: its days start, and the password is stored when the
  fingerprint is on for it). The app counts its locks (`Sessions.lockCount`: the Lock button, the timeout, a stay in
  the background past it with nothing open, the lock alarm): one since Create was tapped leaves the new database in
  the list locked, its key wiped, nothing stored. Made while the app is in the background, its timeout starts then.

## 4. Session and security

- Unlocked state holds the vault, the attachments and the **database key** in memory, as byte arrays that
  are wiped on lock; a reload that reads the same contents again wipes the copy it read at once (the vault shown
  stays). The database key is the composite key of the password, a key file's key or both (below: the
  parts themselves are not kept) and the key derived from it. Using the derived key for reloads and saves avoids running
  Argon2 again (the Mac ROADMAP's "faster saving"); it is derived again when the file's KDF parameters or
  seed changed. The app's own saves keep the KDF salt; KeePassXC gives the file a new one on every save (checked
  on 2026-10-23 with keepassxc-cli 2.7.12: a new salt after each of two saves), so the first reload after a
  KeePassXC edit derives the key again.
- **The key** (keething-spec kdbx.key-files; `core/kdbx` `DatabaseKey.of`): as KeePass and KeePassXC make it,
  SHA-256 over the parts' keys in order — SHA-256(password), then the key file's 32 bytes. Password only:
  SHA-256(SHA-256(pw)); key file only: SHA-256(key32); an empty password is a password (SHA-256(SHA-256("") ‖
  key32) is another key).
- **Key files** (`core/kdbx` `KeyFile`): read as KeePassXC's `FileKey.cpp` reads them, in this order: XML whose
  root is `KeyFile` — `Meta/Version` 1.0 `Key/Data` in base64, 2.0 (`.keyx`) in hex, either case, whitespace
  anywhere, its `Hash` (the first 4 bytes of the key's SHA-256) checked when there; exactly 32 bytes, the key as
  it is; exactly 64 hex digits, the key in hex; anything else, the SHA-256 of the whole file. A file is tried as
  XML when its first byte after a byte order mark and blanks is `<`; XML that does not parse or has another root
  goes on to the next rules. A `KeyFile` XML that fails (another version, no `Key/Data`, not base64 or hex, not 32
  bytes, a wrong `Hash`) is an error, never hashed instead; so is an empty file. The XML is parsed as the
  database's is: a DOCTYPE is refused, so a key file with one is hashed (KeePassXC would read it as XML; no key
  file written by KeePass or KeePassXC has one).
- The session reads the key file through a port (`KeyFileSource`) at every unlock, never at a reload or a save,
  and wipes the bytes read once the key is made; the parts go into the composite at once. A key file that is not
  there or cannot be used fails the unlock before the database is read, by its name or the reason ("The key file
  work.keyx is not there.", "The key file cannot be used: a key file whose key does not match its checksum
  (changed or damaged).", "… Keething may not read work.keyx (allow all-files access)."). With a key file and nothing typed it tries the key file alone, then once the empty
  password with it, as one Unlocking; both rejected are said as the key file alone rejected, and a failure that
  is not a wrong key (the file missing, damaged) is not tried twice. A rejected key says what was given: a
  password alone "Wrong password — or this database needs a key file." (the file cannot tell which, so every
  database says it), a password and a key file "Wrong password or key file.", a key file alone "This key file
  does not open the database (or it needs a password too)." A stored password (§5) rejected while the database
  has a key file, or with the key file missing, is kept: the key file may be the wrong part; so is one rejected
  while the key file was chosen, changed or forgotten since the last unlock that opened it (each database's
  `biometricKeyFileChanged.<id>`, cleared by the next unlock that opens it, or the app's switch off), else Forget on a key file the database needs would cost
  a right password at the next fingerprint.
- **A database's key file** (keething-spec unlock.key-file, kdbx.key-files AC8): chosen on the lock screen (Key
  file…, or Change… / Choose… under the field) or on the database's page in Settings (Choose…, Change…, Forget), with
  the system picker, and remembered by its **path** in the list (never copied); read at every unlock, the
  fingerprint's too. Choosing, changing or forgetting it says nothing more of a locked database's last failure.
  **Nothing typed** with a key file: Unlock works with the field empty. A database that opened so is remembered
  (`opensWithoutPassword`): its lock screen shows no field, only "It opens with its key file alone: nothing to type."
  and Unlock; nothing typed rejected brings the field back (a password was added elsewhere), a typed password that
  opens it forgets it, a key file not there teaches nothing, nor does an unlock whose key file changed meanwhile or whose
  session went (its file located); another database chosen meanwhile does not stop it. A changed key file forgets it too. Nothing typed is never stored for the
  fingerprint (§5). A key file not there is said on the lock screen before anything is typed ("Work needs its key
  file: work.keyx is not in Documents/Keys.") and no fingerprint is asked for.
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
- **A session per database** (keething-spec databases.several-open; `core/vault` `VaultSessions`, the Mac's D5): a
  `VaultSession` and its `BiometricUnlock` for each database of the window (§3.1), each its file's own (its key, vault,
  attachments, folder watcher, reloads and saves) and wiped at its own lock; a typed password goes to its own database
  only. A session is made when its database joins the window, and dropped (locked, never opened again) when it leaves
  the window or the list or its file moves (Locate… starts a new one). The Lock button and the timeout lock every
  database at once; one can be locked or closed alone (⋮ › Databases, a chip's sheet; §3.1); a reload that finds one
  database's password changed locks that one only (its entries' screens close, the others stay), and each lock screen
  says its own database's reason. The vault's screens show while the current database is open. The current one locked
  alone while others stay open: the vault goes on with the window's first open one, which becomes current, the
  search kept ("Work locked: its password was changed elsewhere" when that was why). A lock screen asked for (a
  chip, ⋮ › Databases, Settings) has Back to the open ones. With nothing open: the current one's lock screen, or with
  several in the window the chooser (§3.1). The **app's
  state** (nothing open and why, opening, the open vaults in the window's order) is told once per change, and the
  screens follow it: nothing open clears the list, the search, the card and the editor, the attachments opened
  elsewhere, autofill's matching and the sharing requests, also while another database is still opening.
- Lock: after N minutes in the background (default 5, a choice of 1, 5, 15, 60; turning the screen off
  puts the app in the background), and from a button. **One timeout for the app**, not one per database: it runs
  while the app is in the background and a database is open or opening, from the moment the app left (or the first
  database began to open there: a new database's Create that ended in the background) until the last one locks;
  coming back postpones it for all. The alarm below is set each time this timer starts. Removing the app from recents ends its process, which
  takes the vault with it; leaving the app with Back is the background like any other (Android 17 finishes
  the activity then, so locking on that would ask for the password after every Back). Locking wipes the
  key, the vault and attachments.
- Android freezes an app in the background (about 10 s after it leaves), so the app's timer may
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
    `cacheDir/attachments/<database id>/<uuid>/<name>` (the name made safe: no `/` or `:`, no control characters, at
    most 255 bytes with the extension kept) and handed over as a read-only `FileProvider` URI with
    `ACTION_VIEW`; the system asks which app when more than one can. Those files are removed when their database locks
    (alone too), at every lock of all (the timeout too) and when the app starts (a process that died); a write that a lock overtook writes
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
rules are a port of the Mac's `TouchIDUnlock`: `BiometricSetup` (`core/vault`) for the whole app, `BiometricUnlock` for
each database. Each database has its **own stored password** (`plans/done/android-databases.md` AD5); several can be open
side by side (§3.1): one fingerprint opens every one that can (below), a database's own lock screen opens it alone.

- **Two switches.** The app's, "Unlock with the fingerprint" in Settings, is **off** until turned on; each database
  has its own **Use fingerprint** besides (`useBiometric` in the list, §3.1), on its page in Settings and as "Use
  fingerprint next time" under its lock screen's field. The fingerprint opens a database while both are on. The
  app's switch **allows** a database's to be turned on and turns none on itself; a database's never turns the app's
  on (the owner at `plans/done/android-databases.md` AD5). A database new to the list starts with its own **off**,
  whatever the app's switch: no password is stored for a database nobody turned the fingerprint on for (the Mac's
  rule, a new database taking the app's switch, stored a new database's first typed password unasked). A database
  made on the New database screen (§6.2) has its own as chosen there: "Unlock it with the fingerprint" is shown only
  while the app's switch is on and starts checked (the owner at AD7), and its password is stored at once, as Create
  opens it as a typed unlock (§3.5). "Use
  fingerprint next time" shows while the app's switch is on, the database's own is off, the phone has a strong
  biometric and the lock screen says no problem (its key file not there), also for a database that opens with its key
  file alone; once turned on it stays until the password is stored. A switch going on (the app's, or a database's)
  first deletes what a failed deletion left, or stays off: it would open without anything typed since. With the app's switch off, a database's page says "Off: Unlock with the fingerprint is off in Settings. Once
  it is on, the fingerprint can be turned on here." and its switch cannot be turned on. Turning the app's switch off
  and on again keeps each database's own: those turned on store their passwords again at their next typed unlock.
- **Storing.** Turning a switch on only sets it: the next typed unlock of that database that opens the vault stores its
  master password, and every later one stores it again, so a database password changed elsewhere heals the next time
  it is typed. A password typed while the vault is open, a wrong one, or one typed on a phone without an enrolled
  strong biometric is never stored; nor is nothing typed (a database that opens with its key file alone, §4); nor one
  whose switch went off, or that left the list, while it was being stored. A password that could not be stored says
  why on the database's page ("It could not be set up: …. It tries again at the next unlock with the password.").
- **Each its own.** A database's stored password is kept by its id (§3.1), so a file located again keeps it, and is
  only ever handed to that database: choosing another database keeps each one's (before AD5 the one stored password
  was deleted and the switch turned off then). Its own switch off, or Remove from List, deletes its stored password
  and its days, nobody else's; the app's switch off deletes every one (a delete that fails is said; the switch still
  goes off). The session of a database closed, removed from the list or located again ends: a fingerprint prompt still up
  for it ends as cancelled, and its stored password is never tried, so never rejected. Another becoming current
  changes nothing.
- **How it is kept** (`KeystorePasswordStores`, `KeyringFiles`, `Keyring`; the keyring was chosen by the owner after a
  spike on the Pixel 6, `plans/done/android-databases.md` AD5). One RSA-2048 key pair in the Android Keystore, in StrongBox
  (Titan M2) when the phone has one, created with `setUserAuthenticationRequired(true)`,
  `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` (no time window: each use is one operation that the
  prompt unlocks) and `setInvalidatedByBiometricEnrollment(true)`; making it took 14.6 s in StrongBox on the Pixel 6
  (12 to 26 s in the spike), once, after a typed unlock, out of sight. It seals the **keyring**: a software RSA-2048
  key pair whose private key (PKCS#8) is sealed for the Keystore key, and whose public key seals each database's
  password. Sealing (`SealedPassword`): a random AES-256 key encrypts with GCM, and the RSA public key wraps that AES
  key with OAEP (SHA-256, MGF1 with SHA-1, which the Keystore takes on every Android version). Storing needs only the
  keyring's public key, so it never asks for a fingerprint; opening needs the Keystore's private key, which never
  leaves the Keystore, then the keyring's. The keyring names the Keystore key it was sealed for (SHA-256 of its public
  key), so one made for a key since replaced is known before any prompt. Files in `noBackupFilesDir/biometric/`:
  `keyring` and `databases/<id>`, never backed up nor moved to another phone, where no key could open them. One prompt
  opens the keyring's private key (0.32 s in StrongBox) and it the database's password (milliseconds): the same one
  prompt opens several databases' (`KeyringFiles.openKeyring`, `BiometricPasswordStores.openSeveral`; plan AD13). The one stored password of before (`noBackupFilesDir/biometric-password`)
  is deleted at the first start of the build with the keyring, typed once more; the Keystore key keeps its name of
  before. The switch of before stays the app's, and only the then current database's own is turned on with it (the
  switch was that database's: it went off whenever another became current). An AES-GCM key in the Keystore would need a fingerprint to encrypt too: every typed unlock would ask for
  one to store the password again.
- **Accepted, and why.** The keyring's private key is in the app's memory for the moment of an unlock as a Java key
  object, dropped but not wiped, and the keyring is never replaced: a read of the app's memory then gives a key that
  opens every database's stored password, now and later (the password being opened is in that memory then too). A key
  usable for a few seconds after a check (`setUserAuthenticationParameters(N, …)`) would have opened several
  databases with one prompt without a keyring, but the spike showed it opened by any strong check, another prompt's or
  the phone's own unlock by fingerprint, with no prompt in Keething. The keyring's public key is not authenticated:
  whoever can write the app's private storage could put their own there and receive what is stored afterwards; that
  access is beyond what the app defends against (it could change the app's data as well).
- **Unlock**: the platform `BiometricPrompt` (strong biometrics only, "Use password" as the way out: the
  lock screen's field is the fallback, not the screen-lock PIN) with a `CryptoObject` for the Keystore's key.
  The opened password goes to the normal unlock of its database, as if typed, and is wiped right after. One prompt at
  a time. The lock screen offers it by itself once when the app comes to the foreground locked (a start, a
  return after the timeout), for the database shown then: not after the lock button, nor for another database chosen
  meanwhile (`FingerprintOffers`). With nothing open and several in the window the chooser takes that one offer,
  for all of them (§3.1, below). A database that opens with its key file alone (§4) opens
  after a prompt without a `CryptoObject` (nothing is stored: the fingerprint is the check that it is the user) and is
  never due; one that its key file alone no longer opens gets its field back, and nothing is deleted. No prompt while
  the database's file or its key file is not there. A lock (the button, the timeout, sleep) after the fingerprint,
  also while the stored password is read, opens nothing and deletes nothing.
- **One fingerprint for several** (keething-spec unlock.biometric-several, databases.several-open AC7; `WindowFingerprint`,
  the Mac's `unlockWithTouchID()`; plan AD13): the chooser's (§3.1) opens every database of the window that can — locked,
  both switches on, its file and key file there, its password not due, and stored, or it opens with its key file alone
  — each with its own stored password; the others are not read and say why on their rows and chips. It is asked only
  when one would open, decided without reading anything. One prompt (with a `CryptoObject` for the Keystore's key) opens
  the keyring's private key, and it each stored password, read one after another; the private key is dropped before any
  key is derived. Only databases that open with their key file alone: a prompt without a `CryptoObject`. They then open
  side by side, each judged by its own attempt as soon as it ends (a rejected stored password deleted then, its own).
  A lock after the prompt — of the app (`VaultSessions.lockCount`, which counts a Lock that found every database locked
  as well) or the database's own — opens none of them, also while the stored passwords are read; one locked or closed
  alone meanwhile is not opened and gets no verdict. The fingerprints changed (the Keystore key invalidated, a keyring
  made for another key, a damaged or missing keyring) → nothing opens and every stored password goes; one database's
  sealed copy the keyring does not open → its own goes; the Keystore refusing the keyring after the prompt → said,
  nothing lost. What is said: the list when any opened; else the prompt's own answer over the chooser; each database's
  verdict on its row, its chip and its lock screen ("Fingerprint to set up again" and why). One fingerprint at a time
  for the app, the window's or a database's own lock screen's (`BiometricSetup.asking`). The app's lock count guards a
  database's own fingerprint unlock too (found by AD8's review: Lock all leaves a database already locked as it was, so
  its own count did not change while its stored password was read).
- **Discarding**: a fingerprint added or all removed, or the screen lock removed (the Keystore key is invalidated,
  found before any prompt is shown), a keyring made for a key since replaced, or a damaged keyring → every database's
  stored password, the keyring and the key are deleted and the lock screen says to type the password. A database's
  sealed copy the keyring does not open (sealed for a keyring of before, or changed) → that database's alone, said the
  same way: a typed unlock after a fingerprint change makes a new key and keyring for its database, and each other
  database says so at its own next fingerprint (until then its page says its password is stored, and its lock screen
  offers the fingerprint: accepted, as it is rare). A stored password its database rejects → that database's, and the
  lock screen says to type the new one; a rejected stored password does not shake the field. It is kept while the
  database has a key file (the key file may be the wrong part), the key file is not there, or the key file was
  chosen, changed or forgotten since its last unlock that worked (§4). The Keystore failing to give its key, or
  refusing the operation after the fingerprint (it may be for a moment) → said ("The stored password could not be
  read (…)"), everything kept, no key replaced; so is a file that cannot be read. Cancel or "Use password" → the
  password field takes the focus, nothing deleted. A failed check (too many attempts) shows the system's words and
  keeps it; so does a database that could not be opened for another reason (missing, damaged).
- **The typed password now and then**: with the fingerprint on, the master password is never typed and gets
  forgotten (1Key asks again too). After 14 days without a typed unlock of a database (Settings: 7, 14, 30 days or
  Never, for the app; a stored value that is not one of them reads as 14) its password is due: its lock screen does
  not offer the fingerprint, by itself or with its button, and says "It has been 14 days: type the master password,
  so it is not forgotten" (after a problem, before the reason of the lock); the field takes the focus. The days are
  **each database's** (`lastTypedUnlock.<id>`): typing one database's password does not count for another. Its next
  typed unlock that opens the vault starts its days again, and the fingerprint is back; a fingerprint unlock, a
  wrong password or the cancelled prompt do not count. The stored copy stays meanwhile. The days count from the last
  typed unlock, from turning the app's switch on (every database's) or its own (its), on the wall clock, and only while
  the fingerprint opens it; with no time saved yet (a database new to the list) or one in the future (the clock set
  back) they count from now: it is a reminder, not a barrier, the fingerprint already says who it is. Never is there
  for those who type the password elsewhere every day (the Mac app); Settings then says in red that a forgotten
  password opens its database for nobody.
- **Settings**: the app's switch with "Each database whose Use fingerprint is on opens with it: turn it on under its
  password on the lock screen, or on its page in Databases. Turning this off deletes all their stored passwords." (or
  why the phone cannot), and the days while it is on. A database's page: Use fingerprint with what it does now: "Not
  available: …" when the phone cannot, "Off: its password is typed every time." (with the app's off, the words
  above), "The fingerprint opens it, and nothing is stored." (its key
  file alone), "Paused: it has been 14 days, so the next unlock asks for its password.", "Its password is stored,
  sealed by a key in the phone's security chip (StrongBox) that opens only after your fingerprint. A fingerprint
  added on this phone undoes it.", or "Its password is stored the next time it is unlocked with the password." The
  databases' rows have a fingerprint for each whose Use fingerprint is on, the app's too.
- Not done, and why: `setUnlockedDeviceRequired` (the prompt cannot be shown over the lock screen anyway);
  a marker of the enrolled set like the Mac's `evaluatedPolicyDomainState` (the Keystore's invalidation does
  that job here). Unlike the Mac, the key is bound to the app: the Keystore keeps it per app (UID).
- Checked: the rules with a fake store per database (JVM tests, the Mac's `TouchIDTests` for several databases), the
  files and what each failure costs with a software key (`KeyringFilesTest`), the formats (`SealedPasswordTest`,
  `KeyringTest`), the offer once per visit (`FingerprintOffersTest`); one fingerprint for several (`WindowFingerprintTest`,
  the Mac's several-database `TouchIDTests`; `KeyringFilesTest` for one keyring opening for several), the app's lock
  count (`VaultSessionsTest`, `CurrentDatabaseTest`), the chooser and its run (`UnlockRunTest`, `ChooserRowsTest`,
  `WindowChooserTest`, `NavigationStateTest`). On the Android 17 emulator with a simulated
  finger, before AD5, every path of the one stored password. On the test phone (Pixel 6, release build), with AD5:
  two databases each with its own stored password, choosing between them keeping both, each opened by the fingerprint
  with its own (0.33 s in StrongBox); one that opens with its key file alone opened after the fingerprint with nothing
  stored; the fingerprint asked by itself at a start and not after Lock; a database added later came with its
  fingerprint off and "Use fingerprint next time" shown; a database's own switch off and Remove from List each deleted
  that database's stored password alone; a keyring the build could not read said the fingerprints changed and
  deleted everything without a prompt; the Keystore key made in StrongBox in 14.6 s and 3.1 s. Not on the phone: a
  fingerprint enrolled (the key's invalidation; on the emulator before AD5 only), the days per database, a lock
  while the stored password is read, the Keystore failing. Before AD5 on the phone: the prompt by itself after a start,
  "Use password"; opening the stored password took 0.3 s in StrongBox, then the unlock its usual 2.3 s (the key
  derivation). Android hands the app the accepted fingerprint about 0.9 s after the sensor took it (measured
  2026-10-21, the prompt closing; nothing the app can shorten); from then on the lock screen says "Opening …", the
  StrongBox part included (`BiometricState.opening`).

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
- Groups (keething-spec `plans/android-groups.md` AG3, agreed 2026-10-31) have a canvas of their own:
  https://claude.ai/artifact/P3Vn7ihTPMN3yrTA9LmPeE (the group picker, the editor's group row, Groups & tags over
  every open database, a group's sheet and name dialog, Move to…, the card's Move to group…).

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
- **Welcome** (an empty list: the first start, or every database removed): the app's logo, "Welcome to Keething",
  why a database ("Your passwords live in a KeePass database: one encrypted file that stays yours. Keep it in a
  folder that syncs, and the same file opens on your computer too."), **Create a new database…** (New database, below)
  and **Open a database…** (the system picker), and "Already use KeePass, KeePassXC, KeePassDX or Strongbox? Open your
  database: it stays theirs too, and Keething writes it in their format."
- **New database** (keething-spec `databases.new-database`, `databases.new-database-create`; agreed on the canvas, row
  "Databases: Settings, a database's page, New database"), from the welcome, the lock screen's sheet and Settings →
  Databases, over whatever was shown (its close button and Back leave it, not while the file is written): **Name**
  ("Personal, Work, Family…"; it makes the file name `<Name>.kdbx`, `/` and `:` as `-`, no leading dot, a typed `.kdbx`
  not doubled, at most 255 bytes, and is written as the database's name and its root group's); **Save in** (the list's
  databases' folders, the current one's first with "this database’s", then Documents, and Other folder… through the
  system's folder picker, kept as a path) and the path under it ("Sync/KeePass/Family Shared.kdbx"); a file already
  there is said in red ("There is already a Family Shared.kdbx in Sync/KeePass. A database is never replaced: choose
  another name or folder.") and Create waits. **Master password** and **Again** (a password keyboard; one eye shows
  both, in the code font; a check or a cross beside Again); the **strength line** (four segments; "Weak: shorter than
  8 characters. Anyone with the file can try passwords against it.", "Fair: …", "Strong"; the database's name counts as
  one character; a weak one is allowed); **Suggest a passphrase** (four EFF words joined by `-`, one Capitalised or in
  CAPITALS, a digit and a symbol after words, 3–10 words with − and +, "EFF words, one with capitals, a digit and a
  symbol: about 65 bits. Shown once: write it down.", Another, Use this: both fields, the eye open, "Strong · 4 words,
  about 65 bits"); "Nobody can recover a forgotten master password, not even Keething." **Also require a key file**
  (Optional): **Make a new one…** (a folder through the picker, starting in Documents; the file is `<Name>.keyx`, made at
  Create: "Family Shared.keyx · Documents · made at Create") or **Use one I have…** (any file, §4); Change… goes back
  to the two; a key file in the database's folder is warned "Beside the database, whoever gets the folder gets both.
  Keep it apart: a folder that does not sync.", one under a folder Syncthing syncs (a `.stfolder` in it or above it)
  "In a folder that syncs, it goes wherever the database goes. Keep it apart: a folder that does not sync." (a warning,
  not a refusal); one already there, or whose folder is gone, waits; "Lose the key file and the database never opens
  again. Keep a copy apart from it." **Unlock it with the fingerprint** (§5). The encryption line ("KDBX 4 · AES-256 ·
  Argon2d · opens in about 1 s on this phone · read by every current KeePass app"; Argon2id: "read by KeePass 2.47 and
  later and every current KeePass app") and **Change…**: the **Encryption** page (Format KDBX 4.0; Cipher AES-256 ·
  ChaCha20; Key derivation Argon2d · Argon2id · AES-KDF; Memory halved or doubled, 1–2047 MiB, and Lanes 1–16 for
  Argon2; Time to open 0.1–5 s; measured 300 ms after the last change and one at a time, the time alone scaling what
  was measured: "1.0 s on this phone: 16 iterations, measured. A computer usually opens it faster."; Back to
  recommended). **Create** waits, the first reason beside it ("Name it.", "Type a master password.", "Type the password
  again.", "The two passwords differ.", "Make a key file, or choose one you have.", "Keething may not write to the
  phone's storage: allow all-files access." with Allow all-files access…). **Creating…** ("Creating Family Shared…",
  "Measuring this phone’s speed for 1 s to open, then writing the file and reading it back.", then "Writing the file
  and reading it back."; Cancel only while measuring). A failure is said over Create as "Not created: …" in the Mac's
  words, "this phone" for "this Mac" (a key file there: "… choose another folder"; too little memory for Argon2: "this
  phone has not enough memory for 1024 MiB: choose less on the Encryption page."). Made, the screen goes and the new
  database's empty list shows (§3.5).
- **Databases** (agreed on the canvas, rows "Databases" for one database at a time and "Databases (AD3b, pass 2)",
  "Search across databases", "⋮ › Databases, the chooser and its run" for several open; built in keething-spec
  `plans/done/android-databases.md` AD4–AD13, whose AD3b lists the phone's own choices): the welcome at the first start,
  the lock screen's database line and its sheet, a key file under the field, a database not found, Settings →
  Databases and a database's page, New database; with several open, rows saying their database, the databases not
  open as one line under the header (a tap: its lock screen; a long press: its whole say and menu), ⋮ › Databases
  (a row swiped left closes it, with Undo), `!` and narrowing in the search, the chooser and its run, a new entry's
  Save into. Built: one database at a time (AD4: the welcome, the lock screen's line, sheet and key file, not
  found, Settings → Databases and a database's page), replacing Choose the database; names and colours (AD6: the
  tile's colour, a name read from the file, names alike, §3.1); New database (AD7, above); one list over several
  open (AD9: rows saying their database, the card's database chip, the header's names, opening another closing
  nothing, §3.1); `!` and narrowing in the search (AD10, below); a new entry's Save into (AD11, §3.1, the editor
  below); the line of the databases not open with its sheet, and ⋮ › Databases (AD12, §3.1): Open (a tap: Show only
  this, which types `!Label `, Lock it, Close), Not open (its state and whole say; a tap: its lock screen), Recent (the
  databases out of the window, the last opened first; a tap puts it back and shows its lock screen), New database…,
  Open database…; a row of the first two swiped left closes it with "Closed “Crypto”: still under ⋮ › Databases" and
  Undo; ⋮ says how many are open; the chooser and its run (AD13, §3.1, below). The welcome's large icon is the app's
  logo, as the lock screen's (the launcher icon's layers); pass 1's key glyph there is a stand-in, kept only for key
  files.
- **Lock**: the app's logo (the launcher icon's layers), password field (a password keyboard: nothing capitalised, corrected or learnt), the database's
  line: its tile (its initial in its colour on a soft fill of it, §3.1), its label, folder from the shared storage's root and when it changed ("Sync/KeePass ·
  changed 2 hours ago", "changed yesterday", "changed in March"; "Download · not found" in red) and ⇅; a tap opens
  the **sheet** "Databases": the list's databases (a dot in its colour and its label; a check on the one shown; its folder or "not found"; more than six
  scroll inside it, "9 databases · scroll for the rest"), Open other…, New database… and Remove “…” from list… (asked: "Remove
  “Work” from the list?", "Keething forgets it on this phone, with its stored password. The file stays where it
  is.", Cancel / Remove). Another chosen there joins the others and becomes current: one open shows its vault; one locked is shown with an empty field
  and nothing said of the last one, with Back (and the system back) to the one before while it is open, else with
  nothing open to the chooser (§3.1). A lock screen shown while other
  databases are open (a chip's tap, ⋮ › Databases, Settings) has Back to them. Then the reason of the last lock; a key file not there is said
  first (§4); a stored password that went says why ("The fingerprints on this phone changed. Type the password to set
  up the fingerprint again.", "The database password was changed. Type the new one; the fingerprint will use it."),
  also when one fingerprint for several found it (§5), in place of the wrong password it left until one is typed here.
  In the chooser's run (§3.1) the lock screen has "Unlocking 2 of 3" over it with Stop, no fingerprint button and no
  sheet, a wrong password said over the field, and Skip under it. The field's placeholder is "Master password", with a key file "Master password, if it has one"; Unlock
  is unavailable while the field is empty unless the database has a key file. Under the field the key file (key,
  name, folder or "not there · folder" in red, Change… or Choose…), or **Key file…** while it has none; a database
  that opens with its key file alone has "It opens with its key file alone: nothing to type." and Unlock instead of
  the field. A file **not found** has, in place of the field, "Crypto.kdbx is not in Download any more. Locate the
  file, or choose another database." (on a card: "… is not in /storage/1234-5678. Put the card in, or locate the
  file."), **Locate…** and "or choose another database above". Without all-files access nothing is said missing
  (the file cannot be seen); "Allow all-files access" shows. When the fingerprint
  is set up for the database (§5), its large button with "Touch to unlock" and "or type the password" above the field
  (a database that opens with its key file alone: "or use its key file" above its Unlock); the field then does not
  open the keyboard by itself, and typing hides the button. While the app's fingerprint switch is on and this
  database's is off, a "Use fingerprint next time" switch under the field turns it on for this database, so the
  password typed then sets it up. When the
  password is due (§5) the button is not there and the reason chip says so. A wrong password (or key file,
  §4) shakes the field. The first list after an unlock says how long the key derivation took, once per unlock (the database's name before it
  while two or more are open; Back to a database still open says nothing). On a screen less than 480 dp high
  (a phone in landscape) the logo, name and database are on the left and the field on the right; with the keyboard
  up only the database's line stays beside the field.
- **List**: search results (empty query: recently used, then all by title) over every open database (§3.1); a row =
  title, username, site, and under them the group's path with a folder (`Finanzen › Banken`; none for the root group,
  the path inside the bin for an entry in it, as the Mac's rows); while two or more databases are open the line says
  its database instead of the folder: a dot in its colour, its name in its text colour (the addition of a label smaller),
  then the group: "● Work › Engineering", the root group "● Work". The header (large "Keething", the database's name
  (§3.1) and its entries; with two or more open their labels joined, "Personal and Work · 19 entries"; Lock first and set apart, so that a tap meant for another icon does not lock, then Groups & tags,
  Sharing (§10; a dot while requests wait for an answer) and ⋮ with "Generate a password" (the editor's wand),
  Databases ("2 open", §3.1) and Settings) collapses into a compact opaque bar while the list scrolls, and comes back at the top; it collapses
  too as the search field takes the focus (the keyboard takes half the screen), and comes back when the field
  lets it go with the list at its top. On a screen less than 480 dp high (a phone in landscape) it is the compact
  bar from the start; beside the card (§6.1) that bar has the title only. Recently used: the last 8 entries whose card was opened or from which something was copied,
  kept as refs only (they survive a lock and say nothing without the database; 8 kept per database, §3.1). Syncthing's
  conflict copies and a failed reload are each open database's own banner (the reload's named while two or more are
  open).
  **Search field**: a magnifier and "Search"; while it has the focus, chips over the keyboard (one line, sideways):
  `@ group`, `# tag`, `! database` (only while two or more databases are open), `+ file`, `− bin` (the bin's toggles
  the recycle bin search; none in the Codes list). A tap on one adds its sign to the query, and then the chips are
  what the filter being typed can be, as the Mac's suggestions (Mac SPEC §3.6, `FilterCompletion`, `SearchFacets`):
  the groups by path in tree order, the tags, the file names (a bare `+` first "Any file"), the open databases after
  `!` (a dot, the label in its colour; none with one open), each with its entry count. What is offered follows the
  rest of the query (keething-spec search.suggestions AC6, for one database too): counted over the entries passing
  its other filters (terms aside, the filter being typed left out), so a name that would leave nothing (an empty
  group too) is not offered (`!Work @` offers Work's groups); typed by hand such a filter still filters. A name whose
  entries are in two or more databases is one chip with a dot and a count for each (AC8). Inside the bin search its
  own groups and tags. A tap puts the name in (a database's whole label; quoted when it has a space) with a space
  after it; none matching, no chips. `!x` keeps the open databases whose label starts with `x` (folded as the
  search), and a whole label that database only ("Work" does not keep "Workshop"); two different ones keep nothing;
  only at a token's start (`hi!` is a term). A query of bare signs alone (`@`, `!`, `@ #`) lists nothing and says
  nothing — never the whole vault; the bin search still lists its bins. **Narrowing** (keething-spec
  search.narrowing; the Mac's D11): when the last word typed (two letters or more, a space after it or not) names a
  database, group or tag well — its start, or its initials from the name's first letter (`fs` → Family Shared, `gh` →
  GitHub), never a loose fuzzy match nor notes or values — the chips are "Narrow to" and up to four of them
  (databases with their dot and label, then `@group`, then `#tag`; in each, starts before initials, then by count;
  each kind's best before a second of another), counted over what the rest of the query keeps, words included; one
  that would keep none or all of those is not offered (so no database with one open, none while a `!` filter is
  typed), nor a name with a `"`. A tap replaces the word with the filter and a space (`github fin` → `github
  @Finanzen `); with no ⇥ on a phone keyboard the first chip is not special, and the list stays as typed until a chip
  is tapped. Not while a filter is being typed (its suggestions show), not in the bin search, not in the Codes list
  (its counts would count entries without codes). Closing the keyboard ends the focus and the chips. Back closes the
  keyboard first, then clears the search, then leaves the Codes list.
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
  is compact: the icon (56 dp) beside the title, the group and tag chips under the title, led while two or more
  databases are open by the entry's database's chip (a dot and its label in its colour on a soft fill of it; a tap
  searches that database, `!Work `, as the group and tag chips do). Its bar (Back) is clear at the
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
  with; an existing entry is not moved); while two or more databases are open, **Save into** (its dot, label and
  folder) sits over the group as one block, and its sheet ("The group, tags and username follow the database
  chosen.") lists the open ones with a check on the chosen one, "Not open: …" under them (§3.1). Save shows "Saving and checking…"; saved, the editor goes back to the
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
- **Groups & tags** (the folder in the list's header; the current database's, its label under the title while two or
  more are open; the Mac's Groups & Tags window, §3.25 there, without
  changing anything): **Groups** as segmented rows, "Top level" (the root group) first, then tree order
  (parents first, siblings by name, indented by depth), each with how many entries are in it and below it
  and, when some are in its groups, how many right in it ("3 here"); empty groups too. The counts are the
  current database's (the Mac app's settings entry left out); with two or more open, a tap's search lists the
  other open databases' entries of that name too. Then the **Recycle bin** with its count, when it has
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
- **History** (the card's ⋮, for an entry in the bin too; Mac SPEC §3.22 without restoring):
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
- **Settings**: **Databases**: every database (tile, label, its folder or "Not found · Download" in red, a key for
  one with a key file, a fingerprint for one the fingerprint opens); a tap opens **its page**: tile, label, path from
  the shared storage's root, "Changed 2 hours ago" (of a file not found "Not found at …" with Locate… and "Last opened
  2 weeks ago" when it was), **Key file** (its name and folder, or "None · It opens with its password alone"; Choose… /
  Change…, Forget; "Remembered here by its path, not its contents. It must be there at every unlock, the fingerprint
  too."), **Fingerprint** (Use fingerprint and what it does now, §5; it says where the key is: StrongBox, the TEE),
  **Remove from list…** ("Forgets it on this phone, with its stored password. The file stays."); each acts at once.
  Under the list **Open a database…** (it becomes current; one open stays open, §3.1) and **New database…** (§6.2). Then the app's fingerprint switch (§5) and,
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
  Mac draws a card's and a network's view too); nothing is typed without it. "Not now" is remembered per entry (its ref in the app's settings, as Recent keeps
  refs, §3.1); not offered in the recycle bin, nor for fields typed otherwise.
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
- **Several databases** (keething-spec autofill.fill AC6, autofill.save AC7): the matching entries, the picker and the
  save offer's matching come from every open database, each entry named by its ref (`<database id>:<entry id>`, as
  the list's) in the dataset and the picker, so the same UUID in two databases is two entries; "Unlock Keething"
  only while none is open (the current database's lock screen opens), otherwise the open ones fill. An update goes
  into the entry's own database; a new login goes into the database the last new entry went into, else the first
  open one, with the editor's Save into while two or more are open.
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

### 8.1 Google Play (keething-spec `app.public-release`, was ROADMAP step 8 and RELEASE.md)

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
another fictitious database: two entries with attachments; KDBX 4 exports carry no `Meta/Binaries`), `sample.xml` (hand-made, the real structure) and KeePass's `KDBX_XML.xsd` as published, its source in `KDBX_XML.source.txt` (§3.3). The core's tests use them
the way the Mac tests do (an export's `Meta/Binaries` are not read: KDBX 4 keeps attachments in the file's
inner header). `fixtures/keyfiles/` (the Mac's `Fixtures/keyfiles`, made by keepassxc-cli 2.7.12 from
`kdbx4-aes-argon2d.kdbx`): `keyfile-keyx.kdbx` (`pw` + `keyx.keyx`, XML 2.0), `keyfile-v1.kdbx` (`pw` + `v1.key`, XML
1.0), `keyfile-raw32.kdbx` (`pw` + `raw32.key`, 32 bytes), `keyfile-hex64.kdbx` (`pw` + `hex64.key`, 64 hex digits),
`keyfile-hashed.kdbx` (`pw` + `hashed.txt`, hashed), `keyfile-only.kdbx` (`keyx.keyx` alone); every key and composite
key is in keething-spec `features/kdbx.key-files.md`. `fixtures/created/` (the Mac's `Fixtures/created`, made by its
creator, §3.5): `created-aes-argon2d.kdbx` (the Mac's default, 64 MiB, 4 lanes, 115 iterations), `created-chacha20-argon2id.kdbx`,
`created-aes-aeskdf.kdbx`, `created-keyfile.kdbx` (`pw` + `created.keyx`), `created-keyfile-only.kdbx` (`created.keyx`
alone), each one root group named as the database and no entries; `created.keyx` is Keething's own key file, byte for
byte as KeePassXC writes one. `fixtures/names/` (password `pw`; `kdbx4-aes-aeskdf.kdbx` with its `Meta` changed and
written by the phone's own writer, then opened by keepassxc-cli 2.7.12): `family-shared.kdbx` (" Family Shared ",
`#ff8000`), `family.kdbx` ("Family", no colour), `passwoerter.kdbx` ("Passwörter", `red`). Never use the real database
in tests.

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
  second step is no longer yours alone"); so are custom fields that still hold a code's secret
  (`HmacOtp-…`, KeePass's counter-based codes, not made here, or `TimeOtp-…` under a name KeePass does not read; its
  own `TimeOtp-*` are the entry's code, §3.2), with the same warning. The title, tags, expiry and the custom fields' types always go; attachments
  ("1 attachment stays on this phone"), history and the icon never do. The sender names themselves ("From",
  remembered).
- **What of a wallet never goes** (`WalletSecrets`, `Sending.neverSent`). A recovery phrase (`crypto.seed`), its
  passphrase (`crypto.passphrase`) and a private key typed `crypto.xpub` (`xprv…`) are no part to switch on: What
  to send shows them as locked rows, "Recovery phrase · stays on this phone: whoever has these words has the
  wallet, on every network. To give someone coins, send them to their address." ("Passphrase …: with the recovery
  phrase it opens the wallet"; "Private key …: whoever has it can spend the wallet's coins"). Coins are given by
  sending them to an address; "send me your recovery phrase" is the commonest crypto theft, and a warning clicked
  through once loses the wallet. Untyped, failing closed, by the value, protected ones too: what the offer to type
  calls a phrase (a value whose BIP-39 or Electrum check passes, or a phrase's name with words of a list or a
  protected value; then also the field it would type as the passphrase) in a custom field, the password or the
  notes as a whole; an extended private key by its checksum; a WIF private key (Base58Check, version 0x80 or
  0xEF). Not caught: a phrase inside longer notes; an Ethereum private key (64 hex digits look like any other
  secret). An extended public key (`crypto.xpub`) is off to start with: "With it, <name> sees every address and
  balance of the wallet, past and future, and one leaked key of any of its addresses opens them all" (BIP-32: an
  xpub and any one child private key give every key of the account). Addresses and the derivation path go as any
  field. No format change: an older phone could still send a phrase; received, a field typed `crypto.seed` or
  `crypto.passphrase` has no copy button, as on the card. Sealed with HPKE (RFC 9180, base mode: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM),
  `info` "Keething answer v1" followed by the request's whole SHA-256: it opens only for the very request it
  answers, its name, reason and validity included. Making it removes the request on the sender's phone and keeps
  its id until it expires: one answer per request, the same request read again is "Answered already". The answer
  stays on its screen until Done.
- **Receiving.** The asking phone opens it with the request's private key: "From <name>", the entries with their
  fields (protected ones masked with an eye, each copied as the card copies), and "Only this phone could open it,
  but anyone who saw your request could have made it, and the name is theirs to choose". Under a wallet's address
  (typed `crypto.address`, or read as one): "Check it with <name> another way before paying to it"; an envelope's
  come from whoever gave its pictures, without the note. Save makes each entry a
  new one in the group chosen (the top level to start with), while two or more databases are open in the database
  chosen above it (the one the last new entry went into, else the first open one; another puts the group at its top
  level; once a save was tried the rest go into the same one, waiting for it while it is locked, an entry whose
  interrupted write landed counted as saved, not saved twice; another database locking before that says where they
  go now; keething-spec
  sharing.receiving AC8), each its own checked save (§3.3), a one-time code in the
  format the database's codes use (§3.3; KeePassXC's `otp` link when it has none), tagged `received` besides the sender's tags (once,
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
  strictly: a field named as KeePass's own fields or the code's (since 2026-10-29 all ten of §3.2: an answer from an
  older phone that sends `TimeOtp-Secret`, `-Secret-Hex` or `-Secret-Base64` as a field is refused), two fields of
  one name, a type for a field that
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
