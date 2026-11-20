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

Written first for one person's database (about 35 MB, some 1,900 entries in a dozen groups, imported from
1Password: many custom fields, some one-time codes and attachments), password only (no key file, no
YubiKey), **synchronised with Syncthing** between a Mac and a phone on Android 17; checks on a phone run on a
separate test phone (Pixel 6, Android 17).
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
- Groups & tags (§6.2): the group tree and the tags, each with how many entries it has; a tap lists them; groups
  renamed, made, moved and deleted when empty there. A **templates group** (the group `Meta/EntryTemplatesGroup` names,
  not the root group, the recycle bin or a group in it; KeePassDX puts one with seven forms into every new database) is
  set apart as the recycle bin is (`VaultAssembler`, `Vault.templates`; keething-spec groups.templates): its entries are
  never in the list, search, Recent, the counts, autofill or the reports, and Groups & tags shows it only as a row
  ("Templates · another app's forms, not listed", its count), nothing to tap; no group is made, moved or renamed in or
  into it, nor is it changed or deleted (`EntryLifecycle.isInTemplates`), and a save writes it back as it was.
- Copy any field; the clipboard is marked sensitive and cleared after 30 s.
- Attachments: open and save a copy; add and remove them in the editor (keething-spec edit.attachments). One-time codes: show and copy, and set them up in the editor (keething-spec edit.one-time-code).
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
- Edit, create, recycle and restore entries (delete is undoable from a snackbar), delete one for good from the bin
  (emptying the recycle bin is not offered: one at a time, the owner); custom fields; go back to an earlier version
  from History (keething-spec edit.history-restore).
- Autofill's offer to save a new login or update a changed password, with the password generator for
  sign-ups.
- The password generator (Mac SPEC §3.20): in the editor's password field, and on its own from the list
  (a password without an entry, copied).

Non-goals (v1): SSH agent, e-mail aliases,
reports (but Groups & tags), YubiKey, Twofish, emptying the recycle bin at once (an entry is deleted for good one at a time, §6.2),
reordering custom fields, a Quick Settings tile for codes (Quick note's is §6.4), a one-time code pinned to the notification
shade (dropped: the list's code button and the code copied after autofill do that job, and it would need a
foreground service for little gain), an entry pinned to the notification shade with Copy username / password /
code (dropped 2026-10-22: doable without a service, but it saves only a switch back to the app, whose card
stays open until the lock, autofill covers most logins, and it needs the notification permission; worth it
only for frequent copying into apps autofill does not reach), Google Play (§8.1).

The app stands on its own (the owner, 2026-11-08; keething-spec plans/android-release-1.0.md D5): nothing in 1.0 sends
the user to a computer for what the phone lacks. So changing the master key, the database's name and colour, resolving a
conflict copy, adding, replacing and removing attachments, restoring a version and the backups seen and restored are
planned for 1.0, no longer non-goals.

Planned beyond the first releases (decided by the owner on 2026-10-27; keething-spec `OPEN.md`, `plans/`): several
databases side by side (in: the list, one list over several open, the chooser and one fingerprint for several,
§3.1, §5), key files (in, §4), creating a database (in,
§3.5, §6.2), KDBX
3.1 (read, viewed only or upgraded to KDBX 4 when the user agrees, §3.2), merging conflicting copies (detected, shown and resolved by keeping one until then, §3.4). Groups (decided by
the owner on 2026-10-31; keething-spec `plans/done/android-groups.md`): an entry's group changed in the editor (in, §6.2)
and from its card (a move: in), new groups made from the editor's group picker (in), groups renamed, made, moved and
deleted when empty from Groups & tags (in; over every open database, a block each: in, §6.2); renaming tags and the recycle bin's groups stay with
the Mac.

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
core/kdbx     the KDBX 4 file format (3.1 read, and upgraded to 4.0): crypto, reader, writer, XML tree, KeePass's schema   → domain
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
core/argon2   an Android library: the reference Argon2 in C (vendored, built by the NDK and CMake
              pinned in libs.versions.toml) and its JNI bridge, a kdbx Argon2Engine (§4)  → kdbx
app           Android: the adapters behind the ports (storage, biometrics, clipboard, clock), Compose UI,
              AutofillService (§7), the composition root
                  → vault, search, totp, card, autofill, generator, sharing, argon2 (kdbx only for its engine)
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
- No `Log` of entry values or the master password, ever. The release build writes no state, timing or count to the
  log either: R8 removes `Log.v`, `Log.d` and `Log.i` (`app/proguard-no-log.pro`); `Log.w` and `Log.e` say a failure's
  class, a count or a fixed text, never a message that could quote what was read. Only a measurement build
  (`-Pkeething.timings`, DEVELOPMENT.md) keeps `Log.i`'s timing lines; `tools/build-apk.sh` never ships one.

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
be swapped without touching anything else. **The Storage Access Framework** (keething-spec databases.saf; every build, no
storage permission at all: no `MANAGE_EXTERNAL_STORAGE`, no file read or written by a path, AC1): a database is reached
through a **folder** the user granted in the system's folder picker (`ACTION_OPEN_DOCUMENT_TREE`, kept with
`takePersistableUriPermission`, read and write; never a single file's grant for a database, which a sync's replace
ends), the file addressed by the folder's grant and its path inside at every call (`ProviderFolder`), so a file a sync
replaced is simply the file at that path. Only the phone's own storage and its cards (its document ids are paths; AC12):
a folder or file another app's provider gives (a cloud app) is refused and said ("Keething opens files on this phone's
storage and its card only. Keep the database in a folder that Syncthing, FolderSync or the cloud app's own offline
folder keeps in sync, and choose that."). A save writes a new file beside the database, renames the database aside,
renames the new one in and deletes the old one (`FolderDatabaseStore`; the provider cannot rename over a file, and a
write in place could be sent half-written), noted in the app's storage first so that a save the app did not finish is
put right, at the database's next read and at the app's start, and said over the list with OK until OK is tapped ("The
last save was put right · Keething was closed while it saved Personal.kdbx. The save was finished: the file has the
change." or "… It could not be finished, so the file is as it was before (or as a sync made it since): make the change
again if it is missing."; keething-spec databases.saf AC5). A note the app cannot read (damaged, or `version` newer
than its own) is not acted on: at the start it is said once over whatever shows ("A save may not have finished ·
Keething was closed while saving, and could not read its note of that save, so it could not check or put it right. Open
your databases and check that the last change you made is there; make it again if it is missing.", OK), then kept as
`<name>.save.unreadable` (keething-spec app.upgrade-safety AC3). The version is `Os.fstat` on the provider's descriptor
(device, inode, size, time). Names are taken regardless of case. A **key file** in a folder the list already reaches
goes through that folder; one elsewhere is granted alone in the file picker (`ACTION_OPEN_DOCUMENT`, read only:
`ProviderFile`), since a key file is not rewritten (a sync's replace ends that grant; the key file then says no access,
with Choose…, AC10).

**The list of databases** (keething-spec databases.list; `core/vault` `DatabaseList`, the Mac's): the databases this
phone knows, each remembered by **where its file is** (`FileLocation`: its **path**, standardized: `.` and `..`
resolved; or, from keething-spec databases.saf, a **folder the user granted** and the path inside it) with an **id** of
its own given when it joins the list (a UUID, never the file's: a KDBX header has no stable id and a copy keeps every
UUID), where its key file is (by path, in a granted folder, or a file granted alone), and choices; nothing secret.
**The same file is the same database however it is reached**: the same place on the storage (its volume, `primary` or
a card's id, and the path from its top, as the storage provider names it), case aside, as the storage takes names, so a
path of before, a folder granted and another folder inside it reach one database; paths off the storage compare as
written. Kept as JSON under the setting `databases`: `{"current": <id>, "databases": [record…], "version": 1}` (a
record: `id`, `path` or `file`, `keyFilePath` or `keyFile`, `name`, `color`, `useBiometric`, `opensWithoutPassword`,
`lastOpened` in seconds since 1970, `inWindow`, `checkedInChooser`, `fingerprintAsked`, `backupsOn`, `backupsKept`;
written with sorted keys). `path` and `keyFilePath`
are by path, as every record before databases.saf, which reads as it is; `file` and `keyFile` are objects: a granted
folder's address, its place and the path inside (`grant`, `volume`, `folder`, `path`), or a key file granted alone
(`grant`, `volume`, `place`). The app adds nothing by path any more. **An old record** (kept by its path, before
databases.saf) is never read by it: it says "needs its folder", and its folder is asked for once, the picker opened
there (Choose its folder…, in the sheet "The folder of Personal.kdbx"); a folder that holds its file at that path settles
it, and the record then reaches it through the folder with its id, its stored password, its backups and its choices
(`CurrentDatabase.folderGranted`, AC13). One that does not ("That folder does not hold Personal.kdbx. Choose its file
below, or another folder.": a database directly in `Download` or at the storage's top, whose folder Android gives no
app, moved into a folder of its own) shows its files, and the file chosen there is located, everything kept. Its own
file opened through a folder (Open a database…) is that record too, reached through it from then on. **Every old record a folder of the list holds is reached through it
at once**, its key file too, with nothing asked (`CurrentDatabase.adoptOldRecords`: at the start and after each folder
the list takes), so one folder granted for the Syncthing folder brings every database in it and below it. An old key
file no folder of the list holds says no access, with Choose key file…. A key file granted alone in a folder the list already
reaches goes through that folder, the database's own first (AC9). **A grant the app no longer holds** (its folder
renamed, moved or deleted on a device: AC10) is never the way to a file: a key file chosen there keeps its own grant,
and New database… and Change master key do not offer that folder; a folder granted again (Locate…, a key file chosen in
it, New database…) takes over every record whose grant is gone and whose place it holds, its key file too
(`CurrentDatabase.adoptOldRecords`, the grants held read from `PersistedGrants`; keething-spec T12 databases A3), so
one Locate… brings back a folder's every database. A record reached through a grant to its file alone (which this
version never writes: a later one's, or a list edited by hand) is never read through it: it says no access, with
Locate… (`NoAccessStore`; app.upgrade-safety AC3). A grant nothing of the list is reached through any
more is given back (`GrantKeeper`, AC14), with the notes of a removed database's saves that leave nothing to put right.
A record without `id` or a file, a field of another type or text that is not JSON makes the list **unreadable**: it is kept under `databases.unreadable.<seconds>`, never overwritten, and the
list starts empty, the welcome saying so until a database joins it (`databases.setAside`; keething-spec
app.upgrade-safety AC3); any other field missing reads as its default, unknown ones are ignored. A list of a `version`
newer than the app's is read so, and kept as it was under `databases.version<n>.<seconds>`. The first database added
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

**The databases that need attention** (keething-spec databases.not-open; the Mac's D6b) are one line of chips
pinned under the list's header, with no title (the owner, 2026-11-07), scrolling sideways: the window's databases not
open that were tried to open since the last Lock or timeout (an unlock tried, `Sessions.tried`; skipped in the run,
by Skip or by the run itself; or checked in the chooser and not opened by the fingerprint the chooser asked by itself,
`Sessions.countTried`)
and not locked by the user since (a Password changed one stays: it locked by itself); one nobody tried is under
⋮ › Databases only. Each is read aloud as "Not searched" too while a search is typed; then the open ones that are
read-only (keething-spec databases.read-only-mark AC4), a pencil crossed out in the database's colour, a tap or a long
press showing their sheet: why ("Read only: with the other databases open, too large for this phone to save safely.
Lock them to change it."), **Lock the others** when they are what makes it so (at once, nothing asked:
`Sessions.lockOthers`; it becomes changeable once a save fits, §3.3), **Update to KDBX 4…** for a KDBX 3.1 one (the
question Edit asks, §3.2), Show only this, Lock it. A database not open: each chip is its state's icon in the database's dot colour, in place of its dot, and its
label, with no word for the state (keething-spec databases.not-open AC8; `NotOpenState.glyph`, one icon per state:
Locked a lock, Opening… a spinner, Fingerprint to set up the fingerprint, Not found a
folder with a question mark, No access a folder crossed out, Needs its folder a folder with a plus, Needs its key file
a key crossed out, Cannot be read a warning triangle, Wrong password a lock with an exclamation mark, Password changed
a lock with a turning arrow, Skipped a skip-forward mark; the state's word is read aloud, its whole say in its sheet).
The state is the first that applies (`NotOpenDatabases.state`, the Mac's order): Opening… while it opens; Not found, No access, Needs its folder (an old
record, §3.1), Needs its key file (by the files as they
are now, so an old failure goes once the file is back); what typing cannot fix (its key file cannot be used, Cannot be
read); the fingerprint's verdict (Fingerprint to set up again: the fingerprints changed, or its stored password was not
accepted; kept per database until one is stored again, `BiometricState.setUpAgain`); Skipped (by the chooser's run, below);
Wrong password; Password changed; Fingerprint to set up (nothing stored yet; a reminder due is no state, §5);
Locked. The states follow the sessions and the fingerprint at once, and the files whenever those change or the list
comes back. A tap shows its lock screen with Back, the search kept for when it opens (its entries join the list, its
chip goes); Lock all clears it as ever. A long press opens its sheet: its whole say ("Locked · tap to type its
password", "Not found at Sync/Crypto.kdbx", "No access to Sync/Work any more: locate Work.kdbx to choose its folder
again", "Needs its folder: choose Sync/KeePass once", …), Unlock… (not while not found or unreadable), **Restore its
newest backup…** (its date, size and how many are kept) and **Other backups…** while its file is not found and it has a
backup that would open (keething-spec databases.not-open AC5: both open its Backups, §6.2, in a sheet, the newest's
restore asked at once), Locate… (not found, no access) or Choose its folder… (an old record),
Choose key file… (it has one), Close.

**The chooser and its run** (keething-spec databases.chooser; the Mac's D6b, D7; plan AD13). With nothing open the app
shows the lone database's lock screen, or with several in the window the **chooser**, "Open your databases": each
database of the window with how it opens (Fingerprint, Password, Password + work.keyx, Key file work.keyx, Fingerprint
to set up, Fingerprint to set up again; one open or opening says Open or Opening…, unchecked; `ChooserRows`); what typing cannot open (not
found, no access, needs its folder, its key file not there or no access or unusable, cannot be read) has no check and
says why, with Locate… for one not found or without access and Folder… for one that needs its folder; a row's long
press opens its sheet (its tile, name and folder; **Remove from list…**, asked first as on the lock screen's sheet), so
a database leaves the list without being opened (keething-spec databases.chooser AC12); one not found with a backup
that would open says "Not found · has backups", and a tap on it opens its sheet too, with "Not found at …", Restore its
newest backup…, Other backups… and Locate… (keething-spec databases.not-open AC5);
the others are checked until the user unchecks them (`checkedInChooser`, kept at once; opening or locking one changes
no check, databases.chooser AC9 `partial`). Past five rows the list scrolls (four
and a half show, "9 databases · scroll for the rest"). **Unlock N databases** starts the **run** (`UnlockRun`,
`CurrentDatabase.run`): first one fingerprint for the checked ones it would open (decided without reading any stored
password; nothing asked when none would; a cancelled or failed one leaves them to their passwords, nothing said; a lock
under its prompt starts no run), then the checked ones it did not open asked one after another, each on its own lock screen with "Unlocking 2 of 3"
over it (a segment per database, what opened, Stop), its line, its field with its key file, no fingerprint button,
and Skip; a password goes to that database only (its typed path: stored for the fingerprint when
that is on for it); a wrong one stays to be typed again ("Wrong password — or this database needs a key file. Type it
again, or skip it."); one typing cannot open (not found, its key file not there, closed meanwhile — looked at when the
run reaches it — or a try that fails so) is skipped by itself, and one opened meanwhile counts as opened. At the end
"2 of 3 open · Family Shared: skipped · Crypto: not found" over the list, which shows on the first one opened (the
unlocks the run opened count as told, so their own "Unlocked · …" does not take its place), or over the chooser when
none opened; the skipped ones say Skipped on their chips until they do something else or another run starts, and those
it skipped by itself wait on the line with their state (Not found, Needs its key file). A lock ends the run with nothing said, also one with nothing open yet (the app away past its timeout), and
nothing typed after it opens anything; Stop (and Back) ends it where it is. **Unlock with the fingerprint** (while it
would open one) and, once in a visit that began locked, the chooser by itself ask one fingerprint for every database
that can (§5): the list then shows on the first one opened, and after the chooser's own the checked ones it did not
open wait on the line (keething-spec databases.chooser AC1); when it opens none the chooser says why ("The fingerprint
was cancelled.", "The fingerprint failed: ….", "The fingerprints on this phone changed: type the passwords to set up the
fingerprint again.", "Could not read the stored passwords: …."), and only there. **Open other…** and **New database…**
as on the lock screen: a lock screen asked for with nothing open (there, or from a lock screen's sheet) shows in place
of the chooser with Back to it, and a lock forgets it; a new database opening with nothing else open shows its own lock
screen ("Opening …"). Lock all leads to the chooser, with no fingerprint by itself.
There is **no default path and no migration**: the single path of before
(`databasePath`) is left as it was and read no more, so the first start after the list shows the welcome (§6.2). A
database is added by **Open a database…** (keething-spec databases.saf AC2, AC3; `ChooseDatabaseSheet`): a sheet of the
folders the list is already reached through, each opened into its folders and its KeePass files (`.kdbx`, case aside;
hidden files and Syncthing's own aside; "In the list as “Personal”" on one the list has), and **Choose a folder…** for the
system's picker, then the files of the folder chosen; with no folder granted yet the picker opens at once. A folder
chosen there that a granted one holds is reached through that one (the new grant goes back), so nothing is asked
twice. Under Choose a folder… what Android gives: "Android gives no app the whole storage or Download itself: keep
databases in a folder of their own, such as Download/KeePass or Sync/KeePass." (its picker refuses those two; said in
the error colour once the picker closed without a folder, AC3). **Locate…** gives a database whose file moved its new
file through the same sheet, titled "Locate Personal.kdbx", the picker at once when its folder's access is gone (AC10;
it keeps its id, its backups and its stored password; one open locks first; a file another database of the list has is
refused: "That file is already in the list as “Personal”."); **Remove from List** forgets it with its stored password
and its backups (its folder in the app's storage deleted; keething-spec databases.list AC6) and leaves the file; the current one removed, the window's first left is current (one not in the window stays out),
else the list's first. Every grant a picker gave that the list does not take is given back after the list's next
change, and at the next start (`GrantKeeper`, AC14).

**One list over the open databases** (keething-spec databases.one-list; the Mac's D6): an entry is its database's id
and its own id together, its **ref** `<database id>:<entry id>` (`:` is in neither a KeePass UUID nor a database id),
so the same UUID in two databases (a copy of a file keeps every UUID) is two rows, two cards and two places in Recent.
The list works on copies whose id is the ref (one search ranks them all); the card, the editor, the history, a password
full screen and a move to the bin or out of it act on the entry in its own database, through its session, saved into
its file; a row's Delete follows its own database's recycle-bin setting. `-` lists every database's bin, the
databases in the window's order, each in its file's order reversed (the entries moved in last first, a group deleted
into the bin elsewhere with its entries before them). **Recent**
and the declined type offers (§6.3) are kept by ref: 8 are listed in all and 8 kept per database, so a locked
database's entries are not pushed out by those used meanwhile; its entries are hidden while it is locked and come
back when it opens; an entry gone from its open database is dropped, a database that left the list takes its refs
with it, and an id stored before several databases becomes the ref of the first open database holding it, else it is
dropped (no migration step). While **two or more are open** each row says its database ("● Work › Engineering", the
root group "● Work") and the card's place chip starts with its database (§6.2); with one open nothing of this shows.
**A new entry** (keething-spec databases.one-list AC4–AC6) goes into the database the search names (`!Work`), else the
open card's beside the list (two panes), else the one the last new entry went into (kept across starts), else the
window's first open one, among those with a group; while two or more are open the editor's **Save into** chooses among
the open ones (those of the window not open are named under them, not offered), and another choice moves the draft:
the group goes to that database's top level, the tags offered are its, and the username becomes its default unless it
was changed. A new entry's draft goes when its own database locks; another database locking leaves it (a save in
flight keeps its sheet). Autofill fills and saves from every open database, and received entries choose theirs too
(§7, §10). Groups & tags has a block per open database; sending reads entries from any open one. The search's **`!` filter** keeps one database's entries (§6.2): the index has each entry's
database label, also with one open (`!Personal` typed by hand still filters), and autofill's picker as the list.

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
Mac's Syncthing): the side-loaded build used all-files access, until keething-spec databases.saf replaced it (stage S4,
2026-11-02; what its stage S0 measured is in its plan.md). The probe followed every change from the Mac,
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

- The database is read on unlock and again when the file changed (size, modification time or inode; the folder's
  notices while the app runs, and a check on every return to the foreground). The reload uses the
  in-memory key (§4), so it does not ask for anything.
- A file caught half-written (bad checksum / HMAC) keeps the current vault and tries again on the next
  change (as on the Mac: damaged ≠ wrong password).
- A KDBX 3.1 file (KeePassDX's new databases, KeePass 2.x's when nothing needs 4.x) is read too (keething-spec
  kdbx.kdbx3): AES-KDF from the header's transform seed and rounds, the stream start bytes (other bytes there are a
  wrong key: 3.1 has no header HMAC, so a damaged start reads as one too, as in KeePass), the HashedBlockStream with
  each block's SHA-256, protected values with Salsa20, attachments from `Meta/Binaries`, `Meta/HeaderHash` checked
  when there. AES is decrypted without its padding removed, so the start bytes are compared first: a payload cut
  short (not a whole number of blocks) or with bad padding is a damaged file, never a wrong key, and a reload that
  meets one keeps the vault rather than locking as "Password changed". KDBX 2 and older are refused as unsupported,
  the version named; KeePass 1.x's file (`.kdb`, its second signature `0xB54BFB65`) is named so, with the advice to
  import it in KeePassXC or KeePass 2 and save it as KDBX, and KDBX 2's pre-release signature (`0xB54BFB66`) as the
  old version its bytes give. Keething changes databases only in KDBX 4, so a 3.1 file
  asks once per opening: **upgrade it to KDBX 4, or view only** (`Kdbx3Question`). Its lock screen asks before the
  key, the version told from the file's first 12 bytes (`VaultSession.isKdbx3File`): a card in place of the password
  ("This database is KDBX 3.1": Keething changes databases only in KDBX 4; KeePass 2.35+ and KeePassXC 2.3+ read
  KDBX 4, older apps do not; the 3.1 file is kept as a copy beside it; View only / Upgrade), the fingerprint offered
  only once it is answered. Opened another way (the chooser's run, a fingerprint over the list, autofill), it opens
  viewed only and the same question shows over the list. Every lock forgets the answer. Viewed only, it reads like
  any other; Edit, New and a one-tap change (refused before the file is read, `SaveFailure.ViewOnly`) ask the
  question again, and Save into leaves it out. **The upgrade** (`VaultSession.upgrade`, `VaultSaver.upgrade`) runs
  as soon as it is open, as a save in its turn: the file read, opened for the upgrade (`KdbxReader.openForUpgrade`:
  the tree put the way KDBX 4.0 holds it, `Kdbx3Upgrade`: attachments from `Meta/Binaries` into the inner header,
  each `Ref` from the attachment's `ID` to its place there, `Meta/HeaderHash` gone, ISO times to base64 seconds),
  written as KDBX 4.0 with the cipher, compression and key derivation as read (AES-KDF with the 3.1 file's transform
  seed and rounds, so the same key opens it; ChaCha20 for protected values) and read back: every entry, group and
  setting as before, times to the second (`SaveCheck.verifyUnchanged`). Before the backup and the replace, the 3.1
  file is kept beside the database as `<stem> (KDBX 3.1)<extension>`, never over a file there (` 2`, ` 3` … before
  the extension; `FolderDatabaseStore.keepBeside`); it is not a backup and nothing prunes it. A copy that cannot be
  kept stops the upgrade with nothing written (`SaveFailure.CopyNotKept`); a file that is KDBX 4 by then is read again.
  The window says "<file> is KDBX 4 now. The KDBX 3.1 file is kept beside it as “…”.", or why it was not upgraded.
- A missing file (Syncthing in the middle of a replace) is retried every second for 10 s before it is
  reported; the vault shown stays meanwhile.
  With file versioning on (it was on for the test folder on the emulator), Syncthing first moves the old file
  into `.stversions` and then renames the new one in, so for a moment there is no file.
- Shared storage is a FUSE file system: right after a change `stat` can still show the old file (measured with
  inotify: stale at the event, fresh 0.3 s later), so each burst of notices is given again 1 s after the last.
- The database's folder (§3.1) is watched through the provider (`ProviderFolder.watch`; no `FileObserver`, nothing by
  path): a
  `ContentObserver` on an open cursor of the folder's children (the provider watches a folder only while one is open),
  held through an unstable client so that the provider's process ending does not end the app's; its notices then
  stop without a word, and the watch starts again at the next read or version check (the return to the foreground).
  Each burst of notices is given at once and again 1 s after the last.
- One Syncthing replace brings several events (rename, then the modification time); a reload decrypts the
  file with Argon2 again, so the reload waits for the folder to be quiet for 1.5 s and runs once. A version
  that failed to open (damaged) is not read again until the file changes.
- A read that fails (an error of the card, a failure of the provider other than "not there") is said as such
  (`OpenFailure.ReadFailed`: "The file could not be read (…)."), and so is no memory for the file's bytes
  (`OutOfMemory`): `VaultOpener` catches both around `store.read()`, never letting them reach the app's scope, where
  they would end the app (keething-spec unlock.lock-screen AC5). An unlock stays locked saying it; a reload keeps the
  vault and says it over the list; the chooser's row says "Read failed" and can be checked again.
- An earlier vault stays intact until the lock (a card may still show one of its entries); the lock wipes
  every vault of the session.
- **Nothing a file holds crashes the app or locks the user out** (keething-spec kdbx.hardening; the review of the
  Mac's engine, 2026-10-30). The key derivation runs before the header's HMAC can be checked, so its parameters are
  checked first, against KeePass 2.x's own limits (`KdfLimits`, the same as a new file's, §3.5): Argon2 version 0x13,
  1 to 2³²−1 iterations, 1 to 2²⁴−1 lanes, memory in whole KiB, at least 8 KiB a lane, under 2 GiB; AES-KDF at least
  one round. A file outside them is refused as unsupported, the parameter named ("This database's key derivation is
  one Keething does not run: Argon2 memory of 4 TiB (KeePass reads less than 2 GiB)."), never as a wrong password or damaged;
  so is Argon2 memory within them but more than the phone can give, refused before it is allocated: with the native
  Argon2 (§4) more than half of the memory the phone has free when the derivation starts ("Argon2 memory of 2 GiB,
  more than the 1.4 GiB this phone can spare now, half of its free memory"), with the Kotlin one more than the app's
  heap allows (512 MiB with `largeHeap`, less 48 MiB for the rest). An allocation that fails is the same refusal. A derivation that runs stops within milliseconds when its unlock or reload
  is given up: a lock gives up the key it runs with (`DatabaseKey.abandon`, checked between about a millisecond's
  steps); a key wiped or given up is never used again (`WipedKeyException`). A protected value is decrypted into its
  secret as it is read, so any character in it, a control character too, opens and is written back as it was; text
  that looks like markup in a plain value stays that text. A time a date cannot hold (outside the years 1 to 9999,
  such as `AAAAAAAAAIA=`, Int64.min) reads as no time and is written back as it was. XML nested deeper than 128
  levels is refused as damaged, by both readers: the tree's walks (writing, copying, the schema's check) recurse once
  a level, and the whole save at that depth runs on a 160 KiB stack in the tests (an Android thread has 1 MiB).
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
  costs the memory of a reload, not that of the save's XML tree. It also reads `Meta/HistoryMaxItems`
  (`EntryVersions.maxItems`), for what a restore would drop (§6.2).

### 3.3 Writing

What the phone writes (`VaultEdit`): an entry's fields (title, username, password, URL, notes, custom fields,
their types, tags, expiry), a new entry at the end of a group's entries, an entry into the recycle bin and back out of it,
an entry deleted for good (`VaultEdit.DeleteForGood`: from the bin, or any entry when the bin is turned off; with its
history, a `DeletedObjects` record, and its attachments nothing else uses dropped as every write drops them; keething-spec
edit.delete-for-good), an entry's one-time code set, changed or removed (`EntryChanges.totp`; the editor's, §6.2, and
an entry received, §10), an entry's attachments added, replaced and removed (`EntryChanges.attachments`, below; the
editor's, §6.2), an earlier version restored (`VaultEdit.RestoreVersion`, below; History's, §6.2). Renaming tags and the
recycle bin's groups are left to the Mac (§1). The groups' edits are built in the save (`VaultEdit.EntryEdit` and
`VaultEdit.GroupEdit`: an entry moved, with an edit or alone, new groups made for a new or moved entry, a group renamed,
made, moved, deleted when empty; keething-spec `plans/done/android-groups.md` AG1, AG2). The editor makes an entry's move
and its new groups (the group picker, §6.2; AG4), the received entries' screen new groups (§10), the card's Move to
group… a move alone, its new groups too, and Groups & tags the groups' own changes (§6.2; AG6), each through the
checked save.

An earlier version restored (keething-spec edit.history-restore; the Mac's `EntryEditing.restoreVersion`, Mac SPEC §3.22):
an entry outside the recycle bin (`EditException.AlreadyInRecycleBin`, said as `SaveFailure.WrongPlace`), version N of its
history counted from the oldest. The version is copied first, then the current one goes into the history as for any
edit (trimmed to `HistoryMaxItems`, so even the oldest of a full history comes back), and the entry takes every element
of the copy (fields, attachments, tags, icon, colours, auto-type, custom data and so the field types and the kind) and
its `Expires` / `ExpiryTime`; it keeps its UUID, its other times and its history, `LastModificationTime` and
`LastAccessTime` now; what it had instead goes with its secrets. The attachments the version refers to are in the file
already (its history item holds them). The save reads the file with that entry's history (`KdbxReader.openForEditing(…,
historyOf)`) and refuses a version that is not there by now or whose `LastModificationTime` is not the one shown
(`SaveFailure.VersionGone`: never another version in its place), an entry changed since as any edit; the check expects
the entry to be the version as the file had it, with the entry's place and creation time, and everything else as before.

A one-time code written (`EntryEditing`, the Mac's alike) first removes every code field of the entry (the ten of
§3.2), so one entry never holds two formats; a save that does not change the code leaves its fields as they are. The
code takes the entry's own format; for an entry without one, the format most of the database's current entries use
(not their histories), and `otp` when there are none or a tie: `otp` as KeePassXC 2.7 writes it (protected,
`TotpSetup`); `TOTP Seed` (protected, Base32) with `TOTP Settings`; KeePass's as `TimeOtp-Secret-Base32` (protected,
unpadded), `TimeOtp-Length`, `TimeOtp-Period` and `TimeOtp-Algorithm`, all four. A code its format cannot hold
(KeePass: more than 8 digits; `TOTP Settings`: SHA-256 or SHA-512) is written as `otp`. The fields go where the
entry's first code field was, else after its other fields; the save's check reads the code back from whichever.

Attachments (keething-spec edit.attachments; the Mac's `EntryEditing.setAttachments`, Mac SPEC §3.18): the entry's
`Binary` items become the list asked for (`AttachmentChange.Keep` an attachment of the entry, under its own name or another
(the Mac renames; the phone does not), `AttachmentChange.Add` a new content, also in a replaced one's place), in that order, after its fields; a kept one keeps its element, its `Ref`
untouched. The old version in the history keeps its own items, so it still refers to the old attachments, and a content
only it holds stays in the file: removing an attachment makes the file smaller only once the history is trimmed past it
(`HistoryMaxItems`), as in KeePassXC; the advice of a database too large says so. A new content goes into the inner header with the protected flag (0x01), as KeePassXC
writes every attachment, unless the same bytes are there already, which it then shares (`AttachmentPool.add`); the file
takes a copy, the change's own is wiped after the save. An attachment to keep that is not on the entry refuses the save
before anything changes (`EditException.AttachmentNotFound`; the entry as loaded had it, so this is a fault, said as the
check's). The check expects the names and sizes asked, and reads each of the edited entry's attachments back: a kept one
must hold the bytes it held, a new one its content (`SaveCheck.verifyAttachmentContents`).

Title, UserName, Password and URL are written protected when their value was, or when `Meta/MemoryProtection` says
so (keething-spec kdbx.round-trip AC6, edit.save-entry AC4): an edit never writes a protected value in the clear (the
review's L7; until 2026-11-04 only `Meta/MemoryProtection` decided). Notes keep their own protection
(`EntryChanges.notesProtected`): an edit writes
them protected as asked, else as the value was, else as `Meta/MemoryProtection`'s `ProtectNotes` says for Notes the
entry did not have; a new entry's Notes take `ProtectNotes` unless asked otherwise. Before notes (AN1) an edit of a
protected Notes wrote it unprotected. An entry's kind (§6.4) is one `CustomData` item written as the field types' is
(`EntryChanges.entryType`, set or removed, an edit with its history). The save's check expects both: the protection
asked, kept, or `ProtectNotes`' where there were no Notes; the kind as written.

The Mac app's save path, unchanged in substance (Mac SPEC §3.16 "How"; `core/vault` `VaultSaver`, `core/kdbx`
`EditableDatabase`):
1. Read the file again; open it as it is read (never held whole: keething-spec app.large-database AC2) as an XML
   tree that keeps everything (unknown elements, history, custom data, other apps' data) and writes it back in order;
   of the file itself only its SHA-256 is kept. A protected value goes into the tree as a secret,
   decrypted straight into it: the tree holds no password in the clear either (§4).
2. Find the entry by UUID; if its `LastModificationTime` differs from the one loaded, to the second, or only one of the
   two has a time (one no date holds reads as none, kdbx.hardening AC8) → "changed elsewhere", nothing is saved, the
   vault reloads. Other entries changed elsewhere are kept. An entry whose UUID another entry of the file shares (only
   a damaged file has two) is not changed at all (`SaveFailure.DuplicateId`: "… repair the database in KeePassXC"):
   the edit would go to the first and the check read the other. A group's edit has no such time:
   it meets the groups as the file has them now, and is refused when they no longer allow it — the group gone
   (`GroupNotFound`), a name taken there by now, case and accents aside (`GroupNameTaken`), a group to delete not
   empty any more (`GroupNotEmpty`), a group that may not be changed so (`GroupNotChangeable`: the root, the bin or in
   it, a move into the bin, into itself or below it, or where it is): nothing is saved and the vault reloads; an
   empty name (`InvalidName`) is refused too, without reloading. New levels for an entry meet the file the same way: a level another app made meanwhile is
   that group (`GroupLifecycle.resolving`).
3. Apply the change as KeePassXC does: the old version into `History`, trimmed to `HistoryMaxItems` (not by
   `HistoryMaxSize`, which KeePassXC also applies: keething-spec idea edit.history-max-size, after 1.0); a new
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
4. Write: same format, new master seed / IV / inner stream key; the KDF parameters and salt kept, so the derived
   key that opened the file in step 1, its header's HMAC checked there, writes it, and nothing in between looks a
   key up or derives one (`EditableDatabase.write`; keething-spec kdbx.hardening AC1); characters XML 1.0 cannot
   hold (U+0000–U+001F but tab, LF and CR, U+FFFE, U+FFFF) are left out of plain values, as KeePass 2.x and KeePassXC
   do (`XmlText`; AC7: such a value no longer fails the save), and out of a title, username and URL even when
   protected; attachments nothing refers to dropped, identical ones
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
   the file already had is kept and no reason to refuse); open the new bytes with the built-in reader, with the
   derived key of step 1 alone (`EditableDatabase.readBack`; AC1), plain values expected as step 4 writes them; every
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
   bin is inside the group changed, its entries' paths with them. An entry deleted for good: gone, every other entry
   and every group as before.
6. If the file on disk changed meanwhile (its SHA-256 read again, and by its version once more as the store replaces
   it), do it all again on the new file (once more, then fail).
7. Back up the old file into app-private storage, once a session (keething-spec databases.backups AC1): not when the
   database's switch is off (its record's `backupsOn`, §6.2), not when the file is the one this session's last save
   wrote (its SHA-256, kept by the saver from the unlock to the lock: `VaultSaver.forget` at the lock; a save the lock
   overtook leaves nothing for the next session; a conflict copy taken is not the session's own, so its bytes are backed
   up at the next save), not when one of the database's backups has the same bytes (their SHA-256 in `.sha256` beside
   them, `<hex> <size> <name>` a line, written as each is kept; one without its line, or whose size differs, is read
   once). Decided at each attempt from that attempt's read (step 6), so a file changed elsewhere is copied before it is
   replaced, and a save that did not replace the file changes nothing of it. Nothing is copied when a database is
   opened. The copy goes to `filesDir/backups/<database id>/`, `<name> <local date and
   time>.kdbx`, `_2`… for more within a second, numbered after the last one there, flushed to the storage before the
   replace; the newest as many as the database keeps (`backupsKept`: 5, 10, 20 or 50, 10 by default) in its folder, newest by when they were written, not by the local time in their
   names (a time zone changed would push the newest out: keething-spec kdbx.backups AC3), and only once the replace
   went through: a save that did not replace the file takes its copy back and pushes none out (kdbx.hardening AC9),
   whatever the file was called, other files left alone, so two `Personal.kdbx` keep theirs apart and a
   database located under another name keeps its older ones; the flat files of before the list stay in
   `filesDir/backups` as they were; not in the Syncthing folder, and the app allows no Android backup and no
   transfer to a new phone (`allowBackup="false"` and `data_extraction_rules.xml`: since Android 12 the first alone
   does not stop a transfer), so they stay on the phone) and replace: temporary file in the same folder, then rename.
   The temporary file is
   `.syncthing.keething-<uuid>.tmp`: Syncthing never scans or sends `.syncthing.*.tmp` (its own downloads),
   and the random middle keeps it apart from the `.syncthing.<name>.tmp` Syncthing downloads into. Checked in
   the experiment: while the app wrote an 8 MiB file, the Mac's folder (sampled every 50 ms) never showed the
   app's temporary file, only the final one. Through the folder's grant the database is renamed aside first and the
   new file renamed in (§3.1, `FolderDatabaseStore`; keething-spec databases.saf AC4, AC5). The new file is flushed
   (`FileDescriptor.sync`) before anything is renamed, the backup before the replace (kdbx.hardening AC5). The backup
   is written from step 6's read as it comes, the file never held whole (app.large-database AC2); a read that turns out
   not to be the file of step 1 takes its copy back. The database is reached by documents, never by a path, so a symbolic link never comes into it: the phone's shared
   storage refuses to make one (`ln -s` in `/sdcard`: permission denied, Pixel 6, 2026-11-04) and a card's FAT or
   exFAT has none (kdbx.hardening AC9).
   A file's time cannot be set through the Storage Access Framework, and Syncthing on Android takes times less than
   2 s apart for equal (its `ModTimeWindow`, on any file system but ext2–4): so the new file is written only once the
   clock is at least 2 s from the replaced file's time and from those its earlier saves replaced in the last minute,
   waiting when it comes sooner, and its time is read back and checked after (databases.saf AC6). A time read back still
   less than 2 s from the replaced one's (rare: the wait comes first) leaves the save done, the vault the new file, and
   says so with a warning on every screen that saves ("A sync app may not send the file: … Change something and save
   again."); a file gone right after the replace fails the save. Until databases.saf
   stage S4 the database was reached by path and its time set through it (`PathDatabaseStore`, gone with all-files
   access): the Pixel 6 gave a file renamed over another the old time at its first read (§3.1), which the time set 2 s
   on cured; a new file keeps the time it was written at.
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

The session saves one change at a time with the key in memory (`VaultSession.save`): a change asked for while
another is being saved waits for it, then reads the file it left, so two changes saved together both end up in the
file (keething-spec kdbx.hardening AC4; until 2026-11-04 the second was refused, "try again in a moment"). The vault
is then the written file as read back, and the app's own write is not read again; the outcome names the groups the save deleted
(`SaveOutcome.Saved`, as they were, innermost first: what the file lost, not a guess beforehand) for the snackbar
"Saved and checked. The empty group “Work › Old” was deleted." (two or more: "The empty groups “…” and “…” were
deleted."), which the card says after the editor's save. Refused because the entry changed or went elsewhere, or the file's groups refuse the
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

On a copy of a real database of that size (2026-10-21, on the Mac, `tools/kdbx-probe.sh`, core/vault `KdbxProbe`, the
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

**A database too large to save** (keething-spec app.large-database AC3; core/vault `SaveMemory`, `SaveRoom`,
`VaultSessions`): at each unlock the app works out what a save of the file would need, 16 MiB, 1.3 times every open
database's file and 3.5 times this one's (measured on the Pixel 6 after LD1, keething-spec
plans/android-large-database.md §1, the estimate above each measured peak; entries do not count, bytes do), against
the heap the system gives the app (`Runtime.maxMemory()`, 512 MiB with `largeHeap` there). From 75 % the list says so
once an unlock ("Near this phone's limit": how large it is, its attachments, what to do; OK until the next unlock).
From 90 % (the owner, 2026-11-07) the database opens **read-only** (`SessionState.Unlocked.room`, `readOnly`): a
banner over the list while it is shown ("Read only: too large for this phone"); Edit, New, Move to group…, a group's
changes (its sheet, New group) and a code's Edit stopped before anything is typed or chosen, a dialog saying why over
whichever screen tried (`Sessions.readOnlyRefusal`; a KDBX 3.1 one asks its question there); a one-tap change refused
at once with nothing read (`SaveFailure.TooLarge`); Save into leaving it out (as a KDBX 3.1 one). The judgement is kept until the lock: a reload or a save does not change it, nor does
another database opening. The one opened later is judged with the open ones' saves in mind, so when two would not
both fit it is the later one that opens read-only, and it says to lock the others. When another database locks or
closes, one read-only because of those beside it is judged again (`VaultSessions.thaw`, in the order they were
unlocked, once no save of any database is running: a save a lock overtook still holds its memory): when a save fits
now it becomes changeable, never the other way, and the list says so until OK ("Can be changed again", near the
limit with it: keep the others locked). A read-only database shows a pencil crossed out in its colour in place of its
dot (keething-spec databases.read-only-mark; §6.2, Databases). Its advice:
alone, keep large files outside the database (its attachments the most of it) or split it in two; beside others, lock
them. A save that runs out of memory all the same writes nothing and says to lock the other databases, then to keep
large files outside or split it. Attaching a file asks first (keething-spec app.large-database AC4, with edit.attachments;
`SaveMemory.question`, `VaultSession.roomWith`): one over 10 MiB, or one that makes the database's room worse, near the
limit or past the safe line, judged as an unlock judges it with the file and those added in that editor already, beside
the databases open ("Attach a large file?": how large it is, what it does to the database, the same advice; Attach or
Don't attach). On the Pixel 6 (2026-11-07, `task large:database`'s files): 41.4 MiB alone saves
with nothing said; 82.7 MiB alone is near the limit (its save peaked at 366 of 512 MiB, in 7.8 s); the two together,
whichever opened second is read-only; 100 MiB is read-only alone. Measured besides (2026-11-07): 89.8 MiB alone
saved at a peak of 386 MiB (8.2 s), 79.5 MiB beside the 41.4 MiB one at 396 MiB (7.1 s), the 41.4 MiB one beside it
at 324 MiB.

**A new master key** (keething-spec databases.change-master-key; core/vault `VaultSaver.changeKey`,
`VaultSession.changeKey`, `CurrentDatabase.changeKey`; §6.2 Change master key): a save like any other (in its turn
among the saves, backed up, made onto the file as it is then, replaced only while the file is what was read), whose
change is the key. The file is opened with the key it has, written with the new one and a new 32-byte salt for its
key derivation (AES-KDF: its seed; the costs and the cipher as they were: `KdbxTemplate.withNewSalt`), `MasterKeyChanged`
set and a `MasterKeyChangeForceOnce` that asked for it set back (`EditableDatabase.changeKey`); read back with what the
new key derived for it, nothing else in it changed (`SaveCheck.verifyUnchanged`). A key file alone is KeePass's key of
the key file only, not an empty password with it. Once saved the session goes on with the new key (a lock gives it up
as the save's own); the record has the new key file (or none) and whether it opens with the key file alone; the new
password replaces the stored one for the fingerprint (sealed with the Keystore's public key, no prompt), none with a
key file alone; it counts as a typed unlock. A lock while the file is written does not stop a save within its 10 s
(§4): one that replaced the file ends `SaveOutcome.Saved` with `lockedMeanwhile`, so the record and the stored password
follow all the same, the database stays locked and the form says so ("It locked while the change was written; the
change is in the file. Unlock it with the new key."); any save that reached the file is said so after a lock, the KDBX
3.1 upgrade's too, and one that did not ends `Interrupted` (keething-spec T12 databases B1). A save that fails, or a
lock before the replace, leaves the file opening with the old key, and a key file made for it is removed. Where the database is open with the old key (another phone,
the Mac) the next reload locks it "Password changed" and a stored password it rejects is deleted (§5). Copies of the
file made before keep opening with the old key: the app's own backups (§3.3 step 7) too.

**A backup restored** (keething-spec databases.backups-window AC6-AC9; the database's page, §6.2) replaces the file's
bytes with the backup's as they are, nothing rewritten, a save in its turn among the saves (`VaultSession.restoreBackup`,
`VaultSaver.restore`; a database outside the window, with no session, through `BackupRestorer`, as a locked one). The
backup is read whole and must be the bytes kept (its line in `.sha256`; one without a line gets one now) with a header
that reads (`KdbxReader.isHeaderDamaged`); else nothing happens. Open, it is first opened with the session's key (no
derivation when its key derivation is the file's): one the key opens becomes the vault, as the file read back; one it
rejects (made before a master key change) is restored all the same, and the session then locks
(`LockReason.RestoredOlderKey`: "A backup made before its master key changed was restored. Enter its password, and its
key file then.") and the fingerprint's stored password for it is deleted, to be set up again at the next typed unlock
(`BiometricSetup.restoredOlderKey`); one that does not read for another reason (damaged inside) is refused. Locked,
nothing is read: it unlocks as always. Read-only (KDBX 3.1, too large: §3.3) it is refused at once. Then as a save
(steps 5-8): the file read for its digest, backed up and replaced only while it is still what was read, once more if
it changed. The backup is made whatever the session wrote and with the switch off, `<name> <time> (before
restoring).kdbx` (`DatabaseBackups.keepBeforeRestoring`), unless a backup has the same bytes already: that one is the
file before the restore, so an Undo, which restores it, adds no copy of the backup it undoes. The restored file is not
the session's own (step 7): its next save backs it up unless a backup has its bytes, which the one restored does. Once
replaced, the oldest beyond the number kept go while the switch is on, never the backup just restored, which counts
among them (`FileDatabaseBackups.restored`); off, none go. A failure takes the copy back: the file and the backups as
they were. **Its file gone** (deleted or renamed elsewhere, or not synced yet: the read finds it missing) the backup is
written as a new file at the database's place, as Create writes one (`DatabaseStore.createNew`, `FolderNewFile`: whole
under Syncthing's temporary name, then renamed; never over a file): nothing is backed up before it, the backups stay as
they were and there is no Undo (`RestoreOutcome.Restored.beforeRestoring` null); a file there by then is "Not restored:
there is a Family.kdbx in Sync/KeePass again…" (`SaveFailure.FileThereNow`), no folder "Not restored: Sync/KeePass is not
there any more…" (`SaveFailure.FolderGone`), nothing written (keething-spec databases.backups-window AC6, AC8).

### 3.4 Conflicts

- A `*.sync-conflict-*` copy of a database next to it is shown as a banner of that database (named while two or
  more are open; "Syncthing found two versions: …") with its date and device; the newest copy when there are several.
  The app does not merge: the banner says which one is open, and that both can be merged in KeePassXC (Database →
  Merge).
- **Resolve…** on the banner (keething-spec kdbx.conflict-copies AC6; agreed on the canvas "Conflict copies",
  https://claude.ai/artifact/2N41uYnE2ERe5rX4Rt95Ra, 2026-11-08) resolves that copy without a merge, one copy at a
  time. Its sheet shows both versions in brief (entries, the newest change of one; the copy opened with a copy of the
  session's key, its secrets wiped at once, `VaultSession.copyContents`) and two choices, each saying what it does and
  asked once more in a dialog before it is done:
  - *Keep the open one*: the copy is kept among the database's backups under its own name (§3.3 step 7; with backups off too: it is a file the user did not make, and then it pushes no older one out), then deleted
    (`VaultSession.removeConflictCopy`, `VaultSaver.removeCopy`); a copy that cannot be deleted stays, its backup taken
    back.
  - *Use the other one*: offered only when the copy opens with the database's key and the database can be changed
    (not KDBX 3.1, not too large, §3.3). Saved like any change, in its turn among the saves: the database read for its
    digest, backed up as before a restore (whatever the session wrote and the switch says, unless a backup has its
    bytes: the dialog's "The open one is kept in Keething's backups first" holds), replaced with the copy's bytes as they are (nothing rewritten) while it is still what was read;
    the copy is then deleted, and the vault becomes the copy read back (`VaultSession.takeConflictCopy`,
    `VaultSaver.takeCopy`). A copy that does not open with the key is refused and never locks the database
    ("Password changed" is only the database's own). A copy taken but not deleted is said, and stays on the banner.
    A copy in KDBX 3.1 (KeePassDX's, from another phone) is said so in the choice and its dialog ("The other version
    is KDBX 3.1: once it is used, the database is viewed only until it is upgraded to KDBX 4.";
    `CopyContents.Read.kdbx3`): taking it puts its bytes in place as they are, and the database asks the upgrade
    question again (§3.2); the KDBX 4 file is in the backups.
  The store reads and deletes only a name that is a conflict copy of its database (`DatabaseStore.readConflictCopy`,
  `deleteConflictCopy`). Later: merge in the app.

### 3.5 New database files

The core of making a database (keething-spec `kdbx.new-file`, `kdbx.new-key-file`, `kdbx.kdf-calibration`; the Mac's
SPEC §3.26). The New database screen chooses it (§6.2; keething-spec `plans/done/android-databases.md` AD7).
- **The file** (`core/kdbx` `NewDatabase`): an ordinary KeePass file. KDBX 4.0, gzip, AES-256 or ChaCha20 (Twofish never
  written), Argon2d, Argon2id or AES-KDF with a fresh 32-byte salt (AES-KDF: seed) from `SecureRandom`; the KDF
  parameters as KeePass writes them (`$UUID`; Argon2 `V` 0x13, `S`, `P`, `M` in bytes, `I`; AES-KDF under KeePass's own
  id `c9d9f39a…` with `R` and `S`), no public custom data. Parameters KeePass 2.x would not run are refused before
  anything is measured (iterations 1 to 2³²−1, lanes 1 to 2²⁴−1, memory in whole KiB, at least 8 KiB a lane, under
  2 GiB; AES-KDF at least 1 round; `KdfLimits`, the limits a file read is held to too, §3.2). The XML as keepassxc-cli
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
- **Putting it in place** (`core/vault` `FolderNewFile`, through the folder's grant; keething-spec databases.saf AC11):
  the file is written whole under `.syncthing.<name>.keething-new.tmp`, which Syncthing never scans or sends, and only
  then renamed to the name asked; the provider never renames over another (a name taken, in any case, gives "Family
  (1).kdbx", which is removed again while still what was written, and the name is said taken). A database is never
  replaced, not even by a file a sync put there meanwhile; never a check followed by a move. Nothing is ever under the
  database's name until it is whole. The folder is one the list already reaches, or one granted in the form (Save in's Other folder…,
  Make a new one…); a key file the user has is read through a folder the list reaches or its own grant. The form names
  each place by its path on the phone, for its words; `NewDatabasePlaces` keeps which grant reaches it, and
  `GrantedNewDatabaseFiles` reads what the folders hold through the grants (kept until the form shows again: it asks
  while it draws), Syncthing's `.stfolder` looked for from the folder up to the grant's top. What the form granted and
  the new database does not use is given back. Until databases.saf stage S4 it was made by path with `O_EXCL`
  (`PathNewFile`, the owner's choice of 2026-10-29; measured then on the Pixel 6: a hard link and a symbolic link
  refused in the Syncthing folder, a new file keeps its time, a database, a key file and a 4 KB file reached the Mac
  byte for byte, each synced once at its full size).
- **The measurement** (`core/kdbx` `KdfCalibration`, `core/vault` `EncryptionMeasurement`): as the Mac's, the KDF run
  with the memory and lanes chosen and the iterations scaled to the time asked for; the fixed cost of a run believed
  only under half a run, so it errs towards a file that opens sooner; at least 2 iterations or 100,000 rounds; a clock
  that does not move is an error; cancellable between runs. The phone's default (the owner's choice of 2026-10-29):
  AES-256, Argon2d, 64 MiB, min(cores, 4) lanes, **1 s**. Measured on the Pixel 6 (release build, 4 lanes): 1 s gave
  11–24 iterations in six runs (the plain estimate when Argon2's 64 MiB allocation looked like more than half a run),
  files that open in 0.50–1.04 s, the measuring itself 0.84–0.98 s; 0.5 s gave 5 iterations (0.22–0.29 s). The Mac's
  1 s default (`created-aes-argon2d.kdbx`, 115 iterations) takes 4.8–5.0 s here, about five times, not the two to
  three the Mac's page says (the owner's database has 2 lanes); a phone-made 1 s file (24 iterations) opens in
  keepassxc-cli on the Mac in 0.22 s. These are the Kotlin Argon2's numbers: the measurement runs the key derivation
  the phone opens with, so with the native one (§4) 1 s gives about twice the iterations (not measured again).
- **Create** (`app` `NewDatabaseModel`, the Mac's `NewDatabaseModel`; keething-spec `databases.new-database`,
  `databases.new-database-create`): measured on this phone unless the Encryption page measured these choices already,
  then a new key file (made first, removed again when the database is not made; one the user has is only read), then
  the database. Measuring can be cancelled; from the writing on nothing stops it. It runs in the app's scope, not the
  screen's, and **a short foreground service** (`CreatingService`, Android 14's `shortService`, no permission of its
  own; its notification is not shown without the notification permission, which is not asked) keeps the process while
  it runs: removing the app from recents would otherwise end the process in the middle (the owner's choice at AD7's
  start; to be looked into: `.x/RESEARCH-ad7-app-ended-while-creating.md`, not versioned). What a process that ended
  anyway left (a crash, the system's own kill) is cleaned up at the next start: **`CreationNote`** (`core/vault`,
  `filesDir/creating.note`) names each file before it is written, by its grant and its path in the granted folder (by
  its path in a note of before databases.saf, the one file the app still reads by its path), with its SHA-256 and size;
  read at the start off the main thread, a Create waiting for it; its temporary file goes, and a file it names that is
  still empty or byte for byte as written is removed, any other is someone else's and stays, and the app says "“Family Shared” was not created" ("Keething
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
  (changed or damaged).", "… Keething may not read work.keyx any more: choose it again."). With a key file and nothing typed it tries the key file alone, then once the empty
  password with it, as one Unlocking; both rejected are said as the key file alone rejected, and a failure that
  is not a wrong key (the file missing, damaged) is not tried twice. A rejected key says what was given: a
  password alone "Wrong password — or this database needs a key file." (the file cannot tell which, so every
  database says it), a password and a key file "Wrong password or key file.", a key file alone "This key file
  does not open the database (or it needs a password too)." From the second wrong key typed in a row on the lock
  screen (an unlock, or a failure of another kind, starts the count again; a stored password rejected is no typing)
  each adds "A database that also needs a YubiKey (or another hardware key) cannot be opened in Keething." (the file
  does not tell that one is needed; keething-spec app.first-start AC5). A database not read is said by what it is,
  with what to do where something can be done (`OpenFailure.Unsupported.Kind`, `LockTexts`; app.first-start AC3,
  AC4): Twofish ("… In KeePassXC (Database › Database Security › Encryption Settings) or KeePass, choose AES-256 or
  ChaCha20 and save; then it opens here."), KDBX 2 or older (save it again in KeePass or KeePassXC), a newer format
  than KDBX 4 (an update may open it), not a KeePass file, an unknown cipher, key derivation or inner stream (named),
  and Argon2 memory more than the phone gives the app (lower it on the computer); the list's databases not open say the
  same in a few words ("Cannot be read: encrypted with Twofish"; the chooser's row only "Cannot be read"). A file a
  later try may read (damaged or half-synced, no memory, a read that failed) can be checked in the chooser and says
  so on its row ("Damaged or not synced yet", "Not enough memory", "Read failed"), and its chip's sheet offers
  Unlock… (keething-spec databases.chooser AC4). A stored password (§5) rejected while the database
  has a key file, or with the key file missing, is kept: the key file may be the wrong part; so is one rejected
  while the key file was chosen, changed or forgotten since the last unlock that opened it (each database's
  `biometricKeyFileChanged.<id>`, cleared by the next unlock that opens it, or the app's switch off), else Forget on a key file the database needs would cost
  a right password at the next fingerprint.
- **A database's key file** (keething-spec unlock.key-file, kdbx.key-files AC8): chosen on the lock screen (the key in
  the password field chooses one, and takes the chosen one off again; a database that opens with its key file alone
  has Choose key file… while its key file is not there or not reached, §6.2) or on the database's page in Settings (Choose…, Change…, Forget), with
  the system's file picker opened at its folder, and remembered by **where it is** in the list (never copied): through a
  folder the list already reaches, else through its own grant (keething-spec databases.saf AC9); read at every unlock, the
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
- **The native Argon2** (keething-spec unlock.faster-argon2, 2026-11-07): a database's key derivation runs on the
  reference implementation in C (`core/argon2`: P-H-C phc-winner-argon2's `ref.c`, vendored with one marked change,
  `KEETHING.md` there), loaded at the app's start (`KeethingApplication`, `NativeArgon2.load`, which checks one
  reference value first) and plugged into the file format as `Argon2Engine.native`. Its memory is the device's, not
  the Java heap's: the limit is half of `ActivityManager.MemoryInfo.availMem` when the derivation starts (§3). The
  lanes are filled by threads that live as long as the derivation and meet after each slice (the reference code's
  threads, started anew for every slice, landed on slow cores: two lanes took longer than one). A derivation runs on a
  thread of its own; the caller waits a millisecond at a time and calls the key's checkpoint between waits, so a lock
  gives it up within a few milliseconds (the lanes check a flag every 1,024 blocks); its memory, the password's copy
  and the hashes are then wiped and freed (about 0.15 s a GiB). Where the library does not load, or gives a wrong
  value, the Kotlin Argon2 runs as before, within the Java heap. The same bytes as the Kotlin one: its vectors, random
  parameters and every fixture, checked on the phone (`task test:device`). Measured on the Pixel 6 (release build,
  2 lanes, Argon2id): 64 MiB × 42 in 0.95–1.06 s (Kotlin 2.0 s), 256 MiB × 10 in 1.04–1.14 s (Kotlin 2.25 s), 1 GiB × 4
  in 1.88–1.96 s at a peak of 1.29 GB resident (Kotlin refused it); with the screen off a background app gets the slow
  cores (1 GiB × 4 took 8.0 s). The APK grew by 100 KiB (6,788,338 → 6,891,067 bytes; three libraries of 21–26 KB,
  stored for arm64-v8a, armeabi-v7a and x86_64). New files measured at 1 s (§3.5) get about twice the iterations.
- Secrets are wipeable byte arrays (`Secret`): passwords, protected fields, one-time code keys, attachments.
  Protected values are decrypted from the file straight into them, so the XML in memory never holds a
  password in the clear. Not wipeable, and accepted: a `String` while a value is shown, copied or filled;
  the typed master password in the text field until it is cleared; unprotected fields (username, notes); Notes,
  protected ones too (`Entry.notes` is a string, `Entry.notesProtected` says whether the file protects it, and each
  reader that must not show a protected text asks it; keething-spec `plans/done/android-notes.md` P4, as on the Mac);
  the values in the editor while it is open (Compose's text fields hold strings; the editor goes with the
  lock). What a save writes is made into secrets, wiped once the save is over.
- **A lock while a save runs** (keething-spec kdbx.hardening AC3, the owner's choice of 2026-10-30 for both apps: a
  lock waits for a save, as the Mac's Quit does). The lock (the button, the timeout, a database closed) hides the vault
  at once and wipes the session's key; no save starts any more, and one waiting for the save before it never does. The
  save running works on its own copy of the key and may finish for 10 s of the phone being awake (the main thread's
  clock, which stands still in deep sleep). Then it is told to stop and its key is given up and wiped: it stops right
  before the backup or right before the replace, so the file and its backups are as they were. A replace already begun
  goes to its end (§3.1). One that replaced the file is said as saved all the same (`SaveOutcome.Saved.lockedMeanwhile`:
  a new master key's record follows, §3.3), one that did not as `Interrupted`. The ids of the changes the file did not get (stopped, failed after the lock, never started),
  never a value, are kept through the lock; the next unlock of that database says them on the list until OK:
  "Not saved — The change to “GitHub” was not saved: the database locked while it was being saved. The file is as it
  was; make the change again." (a new entry, a move to the bin, a restore, a group's change each named so). A save
  blocked inside the storage provider's own call keeps what it read until that call returns, then stops.
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
- No autofill on Keething's own screens (keething-spec autofill.own-screens): every text field carries
  `Modifier.keepOutOfAutofill()` (Compose reports fields to autofill itself, whatever the window says, and leaves
  out only those whose content data type is None), and each activity's window root excludes its views
  (`secureWindow`). No service, Keething's or another such as Google's, is asked to fill a field there or offered
  the master password or anything else typed there to save. The keyboard's inline autofill suggestions go with it.
- Clipboard: secrets (protected and concealed values, one-time codes) are marked
  `ClipDescription.EXTRA_IS_SENSITIVE` (no preview on Android 13+); plain values (username, URL, notes) are
  not, so their preview shows. Every copy is cleared after 30 s (fixed, not a setting) if it is still the
  one copied: each copy carries a token in its description. A timer clears while the app runs, an alarm
  wakes a frozen app. In the background Android hides the clipboard from the app, so then it clears whatever
  is there, once per copy (a later copy in another app within those 30 s is lost; accepted). A lock leaves
  the clipboard alone: a password copied just before the database or the phone locks can still be pasted for
  the rest of its 30 s (decided 2026-11-15; a lock on a computer clears it, keething-spec security.clipboard
  AC5, not built for ChromeOS). Checked on the
  Android 17 emulator: both ways clear, and the alarm even starts a new process when the old one is gone.
  On the test phone the timer cleared after 30 s in each run (screen off, Doze), and the clipboard was
  empty afterwards; reading it in the background is refused ("Denying clipboard access … not in focus" in
  the system log), clearing is not. The app reads the clipboard's text only for New note from clipboard
  (§6.4), on the tap, while in front, and for a quick note from the clipboard once its database is open and its
  screen has the window focus (before that only the clip's description, once it has the focus, which shows no notice); it leaves the clipboard as it was. A quick note's text is
  kept nowhere but the editor: not in the settings (they hold the quick notes' database's id alone), saved state, a
  log or a draft (nor the app it came from or its page, which travel with it). A text another app hands over
  (Keething note) comes in the intent, so it is in memory while its
  database's lock screen waits: in the screen's model and the system's copy of the intent, both gone with the screen,
  which finishes itself when stopped, unless a screen it opened itself (a key file picker) is in front: then it waits
  for its return or a lock of the app, and with nothing open there is no alarm to count a stay away, so it may wait
  until Keething is back in front or its process ends. A screen re-created after its process died gives up without
  reading the intent again, and nothing of the text is in the screen's saved state.
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
- Network: none (no `INTERNET` permission at all in v1, nor `ACCESS_NETWORK_STATE`, which CameraX's media3 would add and
  the manifest removes).
- Camera: only to read the code of a sharing request, answer or envelope (§10); the permission is asked when Read a code
  first opens, and reading a picture or pasting works without it.

## 5. Biometric unlock

Same idea as the Mac app's Touch ID (Mac SPEC §3.8), with the Keystore in place of the Secure Enclave; the
rules are a port of the Mac's `TouchIDUnlock`: `BiometricSetup` (`core/vault`) for the whole app, `BiometricUnlock` for
each database. Each database has its **own stored password** (`plans/done/android-databases.md` AD5); several can be open
side by side (§3.1): one fingerprint opens every one that can (below), a database's own lock screen opens it alone.

- **Two switches.** The app's, "Unlock with the fingerprint" in Settings, is **off** until turned on; each database
  has its own **Use fingerprint** besides (`useBiometric` in the list, §3.1), on its page in Settings, and offered once
  by its lock screen (below). The fingerprint opens a database while both are on. The
  app's switch **allows** a database's to be turned on and turns none on itself; a database's never turns the app's
  on (the owner at `plans/done/android-databases.md` AD5). A database new to the list starts with its own **off**,
  whatever the app's switch: no password is stored for a database nobody turned the fingerprint on for (the Mac's
  rule, a new database taking the app's switch, stored a new database's first typed password unasked). A database
  made on the New database screen (§6.2) has its own as chosen there: "Unlock it with the fingerprint" is shown only
  while the app's switch is on and starts checked (the owner at AD7), and its password is stored at once, as Create
  opens it as a typed unlock (§3.5). **Offered once, after the password** (keething-spec unlock.biometric AC4, the owner
  2026-11-02): while the app's switch is on, the phone has a strong biometric and a database's own is off and was never
  chosen (`fingerprintAsked` in the list, §3.1: the question answered, its page's switch set either way, or the New
  database form's "Unlock it with the fingerprint" shown), its lock screen has the fingerprint's button beside the field,
  typing or not; it unlocks with what is typed, as the arrow and the keyboard's Done do. A typed unlock that opens it
  (also in the chooser's run; not autofill's lock screen nor a quick note's, which show no list after it and keep
  Unlock beside the field) keeps the typed password in memory (`BiometricUnlock`, `BiometricState.question`) and its
  list asks over itself "Use the fingerprint for “Work”?" ("From now on your fingerprint opens it: the password you just
  typed is kept on this phone, sealed for your fingerprint. Keething asks this once. You can turn it on later on the
  database's page in Settings.", No / Use fingerprint). Use fingerprint turns its own on and stores that password
  ("Your fingerprint opens “Work” from now on."; a database that opens with its key file alone has nothing to store);
  No is kept, and only Unlock is beside the field from then on; put away (Back, a tap beside it), the question answers
  nothing and the next typed unlock asks it again. The kept password is wiped at the answer, when the database locks
  or closes, and when the question no longer stands (its page's switch set, the app's turned off). A wrong password asks
  nothing. A database removed from the list and added again is a new record, asked again. Turning it on asks no
  fingerprint: whoever does it has the open database already, any enrolled finger would pass, and storing needs only
  the keyring's public key (below); the question is answered by whoever typed the password. A switch going on (the app's, or a database's)
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
  after a prompt without a `CryptoObject` (nothing is stored: the fingerprint is the check that it is the user), and
  the reminder asks about its key file (below); one that its key file alone no longer opens gets its field back, and nothing is deleted. No prompt while
  the database's file or its key file is not there, looked at through their grants on the io dispatcher
  (`FilesThere.isThere`: not found, a grant gone or a record kept by its path is not there; a provider failing
  otherwise tells nothing and the read says why) for a database's own fingerprint, the window's, the lock screen's
  offer and the chooser's run, which skips such a database by itself. A lock (the button, the timeout, sleep) after the fingerprint,
  also while the stored password is read, opens nothing and deletes nothing.
- **One fingerprint for several** (keething-spec unlock.biometric-several, databases.several-open AC7; `WindowFingerprint`,
  the Mac's `unlockWithTouchID()`; plan AD13): the chooser's (§3.1) opens every database of the window that can — locked,
  both switches on, its file and key file there, and its password stored (a reminder due holds none back, below), or it opens with its key file alone
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
  found at the next fingerprint asked, before its prompt: with every fingerprint removed nothing is asked, so a
  database's page says its password is stored until one is enrolled and tried), a keyring made for a key since replaced, or a damaged keyring → every database's
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
- **The typed password now and then** (keething-spec unlock.password-reminder, unlock.reminder-not-a-lock; the canvas
  "Password reminder", agreed 2026-11-13): with the fingerprint on, the master password is never typed and gets
  forgotten (1Key asks again too). After 14 days without a typed unlock of a database (Settings: 7, 14, 30 days or
  Never, for the app; a stored value that is not one of them reads as 14) its reminder is due. It is a reminder, not a
  lock (the owner, 2026-10-29: the user has the right to forget the password, and the app cannot recover it either
  way): the database opens as usual, the fingerprint included (by itself, its button, one fingerprint for several),
  nothing said ahead, and once open its list asks once (`BiometricUnlock.reminder`, decided as it opens, so a typed
  unlock asks nothing; `Sessions.reminderQuestion`, one database at a time, after the fingerprint's question): "Do you
  still remember it?", "It has been 15 days since the master password of “Family” was typed.", **Check it** and **Yes**.
  Yes is believed. Check it asks for the password (a secure field with its eye, out of autofill), checked with its key
  file against the key it is open with (`VaultSession.confirmKey`: the composite keys compared, nothing derived; never stored): it matches,
  or "It does not match. 2 tries left." and the typed text stays to be corrected; after 3 tries "It does not match"
  (it stays open, nothing changed; look it up if it is saved in the database, or Change master key…, which opens it,
  and "Keething asks again tomorrow.", Close). Yes or a match start its days again and say "Keething asks again in 14
  days." at the bottom. Put away (Back, a tap beside it, Cancel) or after 3 tries it is put off: asked again at the first
  opening a day later at the earliest (`reminderPutOff.<id>`), never sooner; a lock before an answer puts nothing off. A
  database that opens with its key file alone is asked "Is the key file safe?" ("It has been 30 days. Is the key file
  family.keyx kept somewhere safe, besides this phone?", **Not now**, **Yes**; only Yes starts its days, an Unlock with
  nothing typed does not); one with a password and a key file is asked the password's question with "Keep the key file
  family.keyx somewhere safe too: without it the password opens nothing." A **quick task** — autofill (filling, saving
  a login), the Codes shortcut, a quick note — asks nothing: what it found locked and opened is not asked while it lasts,
  and when the app leaves the foreground each of those whose question still stands is locked again
  (`QuickTasks`, `LockReason.AfterQuickTask`, which the lock screen does not mention), so the next ordinary opening
  asks; one open before the task is left as it is. The days are **each database's** (`lastTypedUnlock.<id>`): typing one
  database's password does not count for another. Its next typed unlock that opens the vault starts its days again; a
  fingerprint unlock, a wrong password or the cancelled prompt do not count. The stored copy stays. The days count from
  the last typed unlock, from turning the app's switch on (every database's) or its own (its), on the wall clock, and
  only while the fingerprint opens it; with no time saved yet (a database new to the list) or one in the future (the
  clock set back) they count from now. Never is there for those who type the password elsewhere every day (the Mac
  app); Settings then says, in the ordinary colour with a warning icon, "Keething never asks. A forgotten master
  password, or a lost key file, opens its database for nobody, not even with Keething." (until 2026-11-13 the password
  was due instead: the fingerprint was not offered until it was typed, and Never was said in red).
- **Settings**: the app's switch with "Each database whose Use fingerprint is on opens with it: its lock screen offers
  it once, by the fingerprint beside the password, and its page in Databases turns it on or off. Turning this off
  deletes all their stored passwords." (or
  why the phone cannot), and while it is on "Ask about each master key every" 7 days · 14 days · 30 days · Never with
  "After this long without its master password typed, Keething asks once, after an unlock, whether you still remember
  it (or keep its key file safe). The fingerprint opens it all the same." (Never: above). A database's page: Use fingerprint with what it does now: "Not
  available: …" when the phone cannot, "Off: its password is typed every time." (with the app's off, the words
  above), "The fingerprint opens it, and nothing is stored." (its key
  file alone), "Its password is stored,
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
  arrow button to unlock; the reason of the last lock as a chip. Changed 2026-11-02 (keething-spec
  unlock.lock-screen-keyboard, its canvas https://claude.ai/artifact/6Dj42ofwFyNuAu1v9YGE7z): the fingerprint is the
  button beside the field in place of the arrow, the key file chosen in the field, no app name.
- List and card: Expressive segmented groups (rows with small gaps, large outer corners); a list row with a
  one-time code shows the live code as a button that copies it, on a fill that drains over its period (the ring until
  2026-11-14; keething-spec card.code-timer, its canvas https://claude.ai/artifact/299z7q9vyCatPJ421tQrKH, variant D).
- A live code on the card, the Codes view's tiles and the editor (card.code-timer): the digits on a tinted fill that drains
  from right to left and fills again in 0.4 s when the next code starts, dimmed over the last five seconds; under them,
  always on a line of its own, "Next in 12 s 731 004", the next code, which takes the code's colour then. A tap on it copies the next
  code ("Next code copied · works in 12 s"); nothing switches by itself: sites are asked to accept the previous code
  (RFC 6238 §5.2), not the next. The list's row keeps the pill alone.
- Card: a tinted tile per field kind; the password row has an eye, copy and a menu; recovery codes are a
  numbered grid with one eye; a copy confirms in a snackbar ("Password copied · clears in 30 s").
- A row's tile or icon and its buttons stay at the top, in the height of a one-line row (a card's field, a list's
  title and line), whatever the row's height: a value shown over several lines, or a list row grown by a group, a date
  or a large font, grows below them (keething-spec `plans/done/android-notes.md` AN5).
- Show as QR can also show the password as text; the Share… warning points to Show as QR as the safer way.
- The canvas also has a code pinned to the notification shade; it was dropped (§1).
- Groups (keething-spec `plans/done/android-groups.md` AG3, agreed 2026-10-31) have a canvas of their own:
  https://claude.ai/artifact/P3Vn7ihTPMN3yrTA9LmPeE (the group picker, the editor's group row, Groups & tags over
  every open database, a group's sheet and name dialog, Move to…, the card's Move to group…). Changed by the owner at
  AG4: the picker shows one level at a time instead of its drawn tree, the new levels one NEW (the editor, §6.2), a
  path on one line cut as fish shell cuts a directory (the list, §6.2), the card's one place chip instead of its
  database and group chips (the card, §6.2); at AG5 the "Not open:" row lost its lock icon.
- Conflict copies (keething-spec kdbx.conflict-copies AC6, agreed 2026-11-08) have a canvas of their own:
  https://claude.ai/artifact/2N41uYnE2ERe5rX4Rt95Ra (the banner's Resolve…, the sheet with both versions, the two
  choices each asked once more, a copy with another key, a read-only database, a failure, dark).
- Restoring a version (keething-spec edit.history-restore, agreed 2026-11-08) has a canvas of its own:
  https://claude.ai/artifact/5cZ3wiPf5Q3qen5zArKtcV (History's Go back to this version…, the question with what changes,
  a full history, going back, the card after it, the recycle bin, a read-only database, a failure, dark).
- Backups on Remove and Not found (keething-spec T12 databases A1, A6, C5, agreed 2026-11-19, with Other backups… added)
  have a canvas of their own: https://claude.ai/artifact/LV2FpzThNAdfj8FStc9aeb (Remove's question with backups and with
  its file not found, Not found's newest backup on the line's sheet, the chooser and the page, the restore with the file
  gone, its end and its failures, autofill's save with the sync warning; light and dark).
- The password reminder (keething-spec unlock.reminder-not-a-lock, agreed 2026-11-13) has a canvas of its own:
  https://claude.ai/artifact/CN2ZK5AubEyFqM2zS9i7WV (the question over the list, Yes, Check it, a password that does
  not match, three tries, a key file alone, a password and a key file, Settings with 14 days and with Never, dark).

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
  monogram with a colour derived from the title, the same as on the Mac. An entry with no title and no web address
  ("(Untitled)" in the phone's language) has a dot in the quiet colour. Nothing is fetched (no network).
- Motion: the list row grows into the card (shared element transition); a one-time code's fill drains
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
- A screen reader (TalkBack; keething-spec `app.accessibility`): every control that acts has a name, an icon or a
  monogram beside a label that says the same is silent (Material 3 1.5's extended button drops its text, so the
  card's Edit is named by hand). A masked value is read as "Hidden", with only its part in clear ("Card number,
  hidden, ending 1111"), never its dots; a secure field gives accessibility services only its length while hidden
  (Compose's `SecureTextField` gives the typed text itself). A one-time code is read with "N seconds left" and nothing
  about it is announced as it counts; the Codes view's tile says its title, username, code and seconds. A recovery
  phrase's words are read "Word 3, …". Swipes and long presses are labelled actions too; section titles are
  headings; the list's banners are announced when they appear, as snackbars and the lock screen's failure are.
  `task a11y:dump` checks the screen shown: a control without a name, a secret in the tree (DEVELOPMENT.md).
- The generator's words are the EFF large word list (CC BY 3.0 US, `assets/licenses`). A recovery phrase is checked
  against BIP-39's ten word lists (MIT, `assets/licenses`; 184 KB, read when first needed).
- QR codes are made by Nayuki's `qrcodegen` (MIT, `assets/licenses`; 38 KB, no dependencies, medium error
  correction as on the Mac). The sharing's codes (§10) and a one-time code's (§6.2) are read by ZXing (Apache 2.0,
  `assets/licenses`) from the camera (CameraX) or a picture: no Google Play services. An SVG picture, which
  `ImageDecoder` does not decode, is drawn by AndroidSVG (Apache 2.0, `assets/licenses`) first, on white.
- Few settings, fixed sensible defaults instead of switches.

### 6.2 Screens
- **Welcome** (an empty list: the first start, or every database removed): the app's logo, "Welcome to Keething",
  why a database ("Your passwords live in a KeePass database: one encrypted file that stays yours. Keep it in a
  folder that syncs, and the same file opens on your computer too."), **Create a new database…** (New database, below)
  and **Open a database…** (its folder, then its files, §3.1), and "Already use KeePass, KeePassXC, KeePassDX or
  Strongbox? Open your database: it stays theirs too, and Keething writes it in their format." Under it, before any
  picker (keething-spec app.first-start AC1, AC2), why a folder: "Keething asks for the folder your database is in.
  Android then gives it that folder and nothing else on the phone; with the folder Keething sees a sync replace the
  file, and saves beside it safely."; and a cloud drive: "On Google Drive, Dropbox or OneDrive? Keep the database in a
  folder on the phone that Syncthing or FolderSync keeps up to date, and choose that folder." The list kept before
  set aside, unreadable (§3.1), a line in the error colour under the title says so: "Keething could not read its list of
  databases. Your databases themselves are untouched: open them again."
- **New database** (keething-spec `databases.new-database`, `databases.new-database-create`; agreed on the canvas, row
  "Databases: Settings, a database's page, New database"), from the welcome, the lock screen's sheet and Settings →
  Databases, over whatever was shown (its close button and Back leave it, not while the file is written): **Name**
  ("Personal, Work, Family…"; it makes the file name `<Name>.kdbx`, `/` and `:` as `-`, no leading dot, a typed `.kdbx`
  not doubled, at most 255 bytes, and is written as the database's name and its root group's); **Save in** (the list's
  databases' granted folders, the current one's first with "this database’s", and Other folder… through the system's
  folder picker, granted; with none granted yet "Keething saves only in a folder you choose." and the picker at a tap,
  keething-spec databases.saf AC11) and the path under it ("Sync/KeePass/Family Shared.kdbx"); a file already
  there is said in red ("There is already a Family Shared.kdbx in Sync/KeePass. A database is never replaced: choose
  another name or folder.") and Create waits. **Master password** and **Again** (a password keyboard; one eye shows
  both, in the code font; a check or a cross beside Again); the **strength line** (four segments; "Weak: shorter than
  8 characters. Anyone with the file can try passwords against it.", "Fair: …", "Strong"; the database's name counts as
  one character; a weak one is allowed); **Suggest a passphrase** (four EFF words joined by `-`, one Capitalised or in
  CAPITALS, a digit and a symbol after words, 3–10 words with − and +, "EFF words, one with capitals, a digit and a
  symbol: about 65 bits. Shown once: write it down.", Another, Use this: both fields, the eye open, "Strong · 4 words,
  about 65 bits"); "Nobody can recover a forgotten master password, not even Keething." **Also require a key file**
  (Optional): **Make a new one…** (a folder through the picker, starting in Documents, granted; the file is `<Name>.keyx`, made at
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
  again.", "The two passwords differ.", "Make a key file, or choose one you have.", "Choose the folder to save it
  in."). **Creating…** ("Creating Family Shared…",
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
  this, which types `!Label `, Lock it, Close; a read-only one, too large or KDBX 3.1, a pencil crossed out in its
  colour in place of its dot and why in place of its folder, "Read only: too large for this phone", as on the card's
  place chip and its heading in Groups & tags: keething-spec databases.read-only-mark), Not open (its state's icon in place of its dot and its whole say; a tap: its lock screen), Recent (the
  databases out of the window, the last opened first; a tap puts it back and shows its lock screen), New database…,
  Open database…; a row of the first two swiped left closes it with "Closed “Crypto”: still under ⋮ › Databases" and
  Undo; ⋮ says how many are open; the chooser and its run (AD13, §3.1, below). The welcome's large icon is the app's
  logo, as the lock screen's (the launcher icon's layers); pass 1's key glyph there is a stand-in, kept only for key
  files.
- **Lock**: the app's logo (the launcher icon's layers; no app name), password field (a password keyboard: nothing capitalised, corrected or learnt), the database's
  line: its tile (its initial in its colour on a soft fill of it, §3.1), its label, folder from the shared storage's root and when it changed ("Sync/KeePass ·
  changed 2 hours ago", "changed yesterday", "changed in March"; "Download · not found" in red) and ⇅; a tap opens
  the **sheet** "Databases": the list's databases (a dot in its colour and its label; a check on the one shown; its folder or "not found", "no access", "needs its folder"; more than six
  scroll inside it, "9 databases · scroll for the rest"), Open other… (its folder, then its files, §3.1), New database… and Remove “…” from list… (asked: "Remove
  “Work” from the list?", "Keething forgets it on this phone, with its stored password. The file stays where it
  is.", Cancel / Remove). Another chosen there joins the others and becomes current: one open shows its vault; one locked is shown with an empty field
  and nothing said of the last one, with Back (and the system back) to the one before while it is open, else with
  nothing open to the chooser (§3.1). A lock screen shown while other
  databases are open (a chip's tap, ⋮ › Databases, Settings) has Back to them. Then the reason of the last lock; a key file not there is said
  first (§4); a stored password that went says why ("The fingerprints on this phone changed. Type the password to set
  up the fingerprint again.", "The database password was changed. Type the new one; the fingerprint will use it."),
  also when one fingerprint for several found it (§5), in place of the wrong password it left until one is typed here.
  A fingerprint switched on with nothing stored yet says "Type the password once to set up the fingerprint." after a
  problem and before the reason of the lock (keething-spec unlock.lock-reasons AC4).
  In the chooser's run (§3.1) the lock screen has "Unlocking 2 of 3" over it with Stop, no fingerprint button and no
  sheet, a wrong password said over the field (from the second in a row with the hardware key sentence, §4), and Skip under it. The field's placeholder is "Master password" (one line); Unlock
  is unavailable while the field is empty unless the database has a key file. The key file is chosen by a key in the
  field beside the eye (Key file…; tinted once one is chosen, red when it is not there; a tap then takes it off); under the field a line is kept
  for it, so that choosing one moves nothing: the key, its name and "· folder" (or "· not there · folder" in red) on
  one line, the folder cut in the middle, only said (a tap does nothing). Beside the field **one** button: Unlock (the arrow),
  or the fingerprint while the field is empty and the fingerprint opens the database; typing turns it into the arrow,
  emptying the field turns it back (keething-spec unlock.lock-screen-keyboard). To a database whose fingerprint was
  never chosen it is the fingerprint, typing or not, and unlocks with what is typed; the question comes over its list
  (§5). A database that opens with its key file alone has "It opens with its key file alone: nothing to type." and
  Unlock instead of the field, the fingerprint beside Unlock while it opens it. A file **not found** has, in place of the field, "Crypto.kdbx is not in Download any more. Locate the
  file, or choose another database." (on a card: "… is not in /storage/1234-5678. Put the card in, or locate the
  file."), **Locate…** and "or choose another database above". A folder whose access is gone (renamed, moved or deleted
  on a device, its grant taken back; keething-spec databases.saf AC10): "Keething can no longer reach Sync/Work:
  renamed, moved or deleted on a device, or its access taken back. Locate the file to choose its folder again." with
  Locate… (the picker at once, at its folder). An old record (§3.1, AC13): "Keething now opens files only in folders
  you choose. Choose Sync/KeePass, the folder of Personal.kdbx: it is asked once." with **Choose its folder…** (the
  picker opened there). A key file not there or without access any more is said on the chip ("Keething may not read
  work.keyx any more: choose it again."); the key in the field takes it off and, tapped again, chooses one. A database
  that opens with its key file alone has no field, so **Choose key file…** under its line while its key file is not
  there or not reached; the one chosen brings the field back until an Unlock with nothing typed learns again that it
  opens with its key file alone. When the fingerprint
  opens the database (§5) the field does not open the keyboard by itself. With the keyboard up the logo shrinks from
  104 to 64 dp and the database's line keeps to one line (the folder cut in the middle, when it changed whole), so
  that the line, the chip, the field and its key file line stay above the keyboard (checked at font scale 1.3). When the
  reminder is due (§5) nothing changes here: it asks once the database is open. A wrong password (or key file,
  §4) shakes the field. The first list after an unlock says how long the key derivation took, once per unlock (the database's name before it
  while two or more are open; Back to a database still open says nothing). On a screen less than 480 dp high
  (a phone in landscape) the logo, name and database are on the left and the field on the right; with the keyboard
  up only the database's line stays beside the field.
- **List**: search results (empty query: recently used, then all by title) over every open database (§3.1); a row =
  title, the username (the site's host only without one, never an app's address: keething-spec search.list AC1, the
  owner 2026-11-06), and under them the group's path with a folder (`Finanzen › Banken`; none for the root group,
  the path inside the bin for an entry in it, as the Mac's rows; on one line, a path too long is cut as fish shell
  writes a directory, the owner 2026-10-31: the group's own name always whole, the levels above it cut to their first
  letter from the left as far as needed, `A › 2 › C › I › Read only`, then `… › Read only`; TalkBack reads it whole); while two or more databases are open the line says
  its database instead of the folder: a dot in its colour, its name in its text colour (the addition of a label smaller),
  then the group: "● Work › Engineering", the root group "● Work". The header (large "Keething", the database's name
  (§3.1) and its entries; with two or more open their labels joined, "Personal and Work · 19 entries"; Lock first and set apart, so that a tap meant for another icon does not lock, then Groups & tags,
  Sharing (§10; a dot while requests wait for an answer) and ⋮ with "Generate a password" (the editor's wand),
  Databases ("2 open", §3.1) and Settings) collapses into a compact opaque bar while the list scrolls, and comes back at the top; it collapses
  too as the search field takes the focus (the keyboard takes half the screen), and comes back when the field
  lets it go with the list at its top. On a screen less than 480 dp high (a phone in landscape) it is the compact
  bar from the start; beside the card (§6.1) that bar has the title only. Recently used: the last 8 entries whose card was opened, from which something was copied or whose site was opened from its row (one in the bin is never noted, and one moved there leaves the list),
  kept as refs only (they survive a lock and say nothing without the database; 8 kept per database, §3.1). Syncthing's
  conflict copies and a failed reload are each open database's own banner (the reload's named while two or more are
  open). Search finds the title, username, URL, Notes, tags, groups and custom fields' names and plain values;
  never a password, a protected value, protected Notes (a note's text by default, §6.4) or a code's key,
  a further code's `otpauth://` link in an unprotected field too (its name is searched; `SearchableText`). A note's row: §6.4.
  **Search field**: a magnifier and "Search"; while it has the focus, chips over the keyboard (one line, sideways):
  `@ group`, `# tag`, `! database` (only while two or more databases are open), `+ file`, `− bin` (the bin's toggles
  the recycle bin search; none in the Codes view). A tap on one adds its sign to the query, and then the chips are
  what the filter being typed can be, as the Mac's suggestions (Mac SPEC §3.6, `FilterCompletion`, `SearchFacets`):
  the groups by path in tree order, the tags, the file names (a bare `+` first "Any file"), the open databases after
  `!` (a dot, the label in its colour; none with one open), each with its entry count. What is offered follows the
  rest of the query (keething-spec search.suggestions AC6, for one database too): counted over the entries passing
  its other filters (terms aside, the filter being typed left out), so a name that would leave nothing (an empty
  group too) is not offered (`!Work @` offers Work's groups); typed by hand such a filter still filters. A name whose
  entries are in two or more databases is one chip with a dot and a count for each (AC8). Inside the bin search its
  own groups (the paths inside the bin, as its rows show them and its search reads them) and tags. A tap puts the name
  in (a group's own name, not its path, so a name at two places is one filter, though each chip counts its own group;
  a database's whole label; quoted when it has a space) with a space after it; a name with a `"`, which could not be
  written back, is not offered; none matching, no chips. `!x` keeps the open databases whose label starts with `x` (folded as the
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
  is tapped. Not while a filter is being typed (its suggestions show), not in the bin search, not in the Codes view
  (its counts would count entries without codes). Closing the keyboard ends the focus and the chips. Back closes the
  keyboard first, then clears the search, then leaves the Codes view.
  **Gestures**: a row swiped right copies the password, swiped left the username; past 88 dp what is under the row
  turns solid with a tick, and let go the row springs back (a side with nothing to copy says "No password to copy" / "No
  username to copy" and does nothing; both are accessibility actions too). A long press opens the **quick actions**:
  copy the username (shown), the password or the one-time code (live; with two or more, Copy one-time code… opens their
  sheet), open the site (as the card's link), then
  Edit, Remove from Recent (a recent row: "Removed from Recent" with Undo, which puts it back in its place, the
  entry itself untouched) and Move to the recycle bin (as Delete on the card, with Undo; left out when the database has the bin turned off,
  Delete for good… being the card's); an entry in the bin has
  the copies, the site, Restore (the list then says "Restored to …", as the card does) and Delete for good… (asked
  first, as on its card).
  A row's code is a pill beside the title, or under it where the pill would leave
  the title less than about seven and a half characters (large text on a narrow phone). It is the entry's first code:
  its own, else its first further one (an entry with further codes only has a pill too); a tap copies it. An entry with
  two or more codes has their number in a small circle at the pill's end, and a tap opens a sheet of them instead
  (keething-spec search.row-codes, the owner 2026-11-15): headed by the entry, each code with its name, live on its
  fill with "Next in 12 s" and the next code under it, as on the card; a tap copies that code or the next one and
  closes the sheet. TalkBack reads the pill as "3 one-time codes", action "Show codes". In the light theme the
  pill and the card's code are fixed blues, not the wallpaper's (its colours made a muddy brown of them on some
  phones).
  A row also shows the paperclip of attachments and a date that has ended or ends within 90 days. An end
  date's status has one phrase wherever it shows (keething-spec card.dates AC6, AC8): the field's line on the
  card, the card's deadline banner and the row after the field's name ("gültig bis · Expires in 36 days"), the
  chip under a card's or a document's face, KeePass's own expiry (its banner then the day, "Expired yesterday ·
  14 Jan 2027"; past its time on its last day "Expired today"); days below 60, months below 24, then years
  ("Expires today", "Expires tomorrow", "Expires in 5 months", "Expired 45 days ago"). Today is the phone's day (its
  time zone), and KeePass's own expiry is the day its moment falls on there, as the editor sets it. A
  payment card's, a Wi-Fi network's, a bank account's, an identity document's, an SSH login's or a crypto wallet's
  row (its fields typed, §6.3) shows the small face instead of the icon (a card in its network's colours, the label
  of a router, a bank with the country's flag at its corner, a small paper document, a terminal's `>_`, a small steel
  plate with the network's badge at its corner) and says which it is instead of the username and site
  (`Visa •• 4242 · 12/26`, `WPA2 · FRITZ!Box 7590`, `DE89 •• 3000 · Commerzbank`, `Passport · Germany · until
  03/2029`, `alex@db.example.com:2222`, `Bitcoin · bc1qar…5mdq`, `Crypto wallet · recovery phrase`; a protected
  number gives no digits); the card's
  header shows it too (`EntryFace`). A
  group or tag chip on the card searches for it; Back clears the search first. A Syncthing conflict copy (with
  Resolve…: a sheet keeping the open version or using the other one, §3.4), a reload that failed (the vault read
  before is shown) and a database too large to save or near that (§3.3) are banners above the list.
- **Card**: as on the Mac (sections, masked values with an eye, live codes with the next one under them on a line of
  their own (§6), tap to copy, links open the browser, attachments open in another app / save to a folder, §4).
  Everything shown is masked again when the app leaves the screen (Home, the screen off, another app in front, an
  attachment or a link opened; keething-spec card.masking AC5), and a vault read again keeps a custom field shown only
  while it is the same field (by name). An empty custom field shows "—" in the secondary colour, said "Empty", with
  nothing to copy (card.fields AC2). A one-time code the app cannot use (a Steam code, a counter-based HOTP link, a
  secret not in Base32, an unknown algorithm; card.totp AC6) keeps its row: the code's tile grey and "This code cannot
  be used here: Steam codes." in the secondary colour, nothing to copy (agreed on the canvas "Code that cannot be
  used", https://claude.ai/artifact/H2JLkfxuwC8NYXbKFf1iw2, variant A, 2026-11-20); the editor's own refusals say the
  same reasons. An app's address (`androidapp://<package>`
  in the URL, a `KP2A_URL…` field or a field typed `url` or untyped; keething-spec card.app-address, canvas
  https://claude.ai/artifact/M7w5or3TN83mFj1DmRPAkE) is shown as the app the phone has: its own icon in place of the
  tile, its name, the address under it, an open button; an app the phone does not have shows the default app icon,
  the package and a button to its page in the store (`market://details?id=`: the store's own icon when one app opens
  such links, an install icon when several do, none when none does). The field's name shows above only when it is
  the user's own (not `URL`, `URL 2`, `KP2A_URL…`); nothing is fetched or written. Every row that copies on a tap
  has the same copy button (username, URL, code, custom fields alike). **Edit** is a button at the bottom
  right, in reach of the thumb; the bar's ⋮ holds Show all or Hide all (while the card has a masked row: every one shown if any is masked,
  else all masked again; keething-spec card.masking AC4), History, Move to group…, Send to a request (§10) and Move to the
  recycle bin (at once, the list offering Undo). **Move to group…** (keething-spec groups.entry-group AC2 from the
  card, `plans/done/android-groups.md` G6): the group picker titled "Move to group", the entry's group checked, new groups
  offered as in the editor ("made with the move"); a group chosen moves the entry at once through the checked save
  (no history snapshot, as KeePassXC moves one), the snackbar "Moved to “Archive › Japan”. The empty group “Travel ›
  Japan › Hotels” was deleted." (the groups the move left empty, as in the editor); the group it is in already does
  nothing. In the recycle bin: Restore in the bar, History and Delete for good… under ⋮, no Edit (with the bin turned
  off, Delete for good… stands for Move to the recycle bin on every card). A note's text and protected Notes:
  §6.4. Custom fields are read by their
  types (§6.3): a field typed `password` is masked with the password's key in front, protected or not. The header
  is compact: the icon (56 dp) beside the title, under the title one place chip (the owner, 2026-10-31): while two or more databases are open the entry's database
  first (a dot and its label in its colour on a soft fill of it), then the group's path, on one line cut as the list
  cuts it (the database's name never cut); with one open a folder and the path; a tap searches there (`!Work @Mail `,
  `@Mail `, `!Work `). In the recycle bin the chip shows the path inside the bin, the bin's own name at its top, and a
  tap searches the bin (`- @Mail `, `- `; card.place-chip AC3). The tag chips always on a line of their own under it; a tap searches the tag. Its bar (Back) is clear at the
  top and opaque once the card scrolls under it; when the large title has gone under, the entry's icon and
  title move into the bar. A password's menu (⋮ on its row): **Show as QR** (full screen at full
  brightness, black on white, for another phone to scan; nothing leaves the device; "Show the password"
  adds it as coloured text; a password too long for one code says so), **Large type** (full screen, each
  character in its own numbered cell, big, monospaced and coloured, a space shown as `␣`, a line break `↵`, a
  tab `⇥`, an emoji with its modifiers or a letter with its accents one character, for typing it on a
  TV or another computer), **Share…** (the system share sheet with the password as plain text, after a
  warning that it goes to another app and maybe its cloud and history; the warning offers Show as QR). Both
  full-screen views read the password from the vault by the entry's id, close on a lock, and are left with
  Back or ✕. The share sheet shows the text and has its own Copy, which the app cannot mark sensitive or
  clear: the warning comes first for that reason. Every share sheet the app opens (this one, an address, a bank
  account's details, a code's pictures or text, §10.3) leaves out Keething's own targets, "Read with Keething" and
  Keething note (`sharing/ShareSheet`): Keething does not share with itself.
- **Entry faces** (agreed on the canvas, rows "Entry faces", "More faces" and "Crypto wallets"): a payment card, a
  Wi-Fi network, a bank account, an identity document, an SSH login and a crypto wallet are drawn as what they are,
  and the face is the interface; the rows under it are only what the face does not show. The Mac draws the same six
  (Mac SPEC §3.13): the data rules are the same on both (`PaymentCard`, `WiFiNetwork`, `BankAccount`,
  `IdentityDocument`, `SshLogin`, `CryptoWallet`, ported one for one), each app draws its own faces. What a face masks
  although the database does not protect it (a card's number, CVC and PIN, a network's key) is masked in the editor
  and in History too, and its copy is marked sensitive (keething-spec card.masking AC2).
  - **Payment card**: a card 1.586:1 (at most 440 dp wide, as every face, §6.1), in its network's colours (Mac `PaymentCardView`;
    Visa, Mastercard, American Express, Mir; another network in the entry's colour), with a chip, the contactless
    mark and the network's mark drawn simply. Its front: the number masked to its last four digits, the holder,
    "VALID THRU" and the month (red when past); a tap on any of them copies its field as stored. Its back: the
    magnetic stripe, the signature strip and the CVC, a tap copies it. **Show** reveals the number and the CVC
    together; **Turn over** or a sideways swipe on the card turns it, animated around its vertical axis. A
    chip under it says "Expires in 2 months" / "Expired 3 days ago" when the expiry is within 90 days or past,
    the only place the card says it (no banner for that field, no row). A number failing its check digit says
    so ("a typo?"). On the face (`PaymentCard.faceRowIds`): the number, an unprotected holder, an expiry that is
    a date, the CVC, and the type when it names the face's network, or the number names none (a protected number,
    an unknown range); a type naming another network than the number's, the PIN and other `card.*` fields are rows
    under it, with copy buttons. A face's own expiry is left out of the card's banner, which says the most pressing
    of the other deadlines.
  - **Wi-Fi network**: the label under a router: "WI-FI NETWORK" with the security as a chip ("WPA2", or "Open ·
    no password"; open and WEP in the warning colours), the network's name large, the key masked (coloured when shown), and the
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
    and check digits in the accent; a Russian 20-digit account as printed, `40817 810 5 0000 1234567`) on one line,
    smaller rather than wrapped (down to 13 sp, where the longest IBAN fits) and cut with "…" only past that
    (keething-spec types.bank-account AC10, the owner 2026-11-16), then the
    BIC, the bank code ("BIK" for a Russian БИК, else "BANK CODE"), the correspondent account and the bank as
    small lines. A tap copies a line; the number and the correspondent account copy **without spaces** (bank forms
    often refuse them or cut them off; a protected number too), and say so in the notice. **Copy IBAN** / **Copy account** copies the
    number; **Share** sends the holder, number, codes and bank as text to the share sheet (for someone who sends
    money; nothing secret, so no warning), in a transfer form's words whatever the app's language (`IBAN`, `Account`,
    `BIC`, `Bank code`, `Correspondent account`); Russian details with their Russian names: Получатель, Счёт, БИК,
    К/с, Банк. An IBAN
    failing its mod-97 check (ISO 7064), or of the wrong length for its country, says so ("a typo?"); a Russian
    account checks against its БИК (the Bank of Russia's key: the БИК's last three digits and the account,
    weights 7-1-3), the correspondent account against `0` and the БИК's fifth and sixth digits. A protected number
    is masked on the face with Show; other protected parts, and the recipient's ИНН / КПП, are rows under it.
  - **Identity document** (`IdentityDocument`, an entry with a `doc.number`): a card of paper (ID-1, 1.586:1 at
    least, taller with large text; a guilloche drawn behind; paper in either theme), its type as the heading
    ("PASSPORT", else "IDENTITY DOCUMENT"), the issuing country with its flag (a code is named: `D`, `DE` or `DEU`
    → Germany; Kosovo's `XK` or `RKS`; a name in English, German, Russian, French, Spanish or the phone's language
    gives the flag; a country it cannot name is shown as written, without a flag), the
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
    secret like the password): shown, a numbered grid of its words, three to a line (two when its longest word would not fit a third: large text, an
    eight-letter word on a narrow phone), its
    count and language as a chip, and its check (`SeedPhrase`): a BIP-39 phrase's checksum in whichever of the
    BIP's ten word lists its words are in (MIT, `assets/licenses`; a typo in any word is caught: "Word 7, “worht”,
    is not a BIP-39 word. A typo?", outlined in red; "The checksum fails: a word is wrong, or two are swapped"; 12,
    15, 18, 21 or 24 words); an Electrum seed (Electrum's seed version, its HMAC-SHA512) is named as one, not failed.
    Numbers and commas written between the words are left out, words of the Latin lists count without their
    accents, and a word is shown as its list spells it. The passphrase (the "25th word") is a line on the plate with
    its own eye. **Neither is ever copied** (the clipboard is where phrases are stolen), wherever it stands (a
    passphrase in an entry that is no wallet too): no copy button, no tap to copy, no QR code, no share; in the editor its value is masked and its text menu has no Copy, Cut or the other
    apps' items ("Ask Gemini", "Translate"), only Paste and Select all, and a new field of either type starts
    protected; the history masks it even when not protected. **Word by word** is a full-screen view, a word at a time, large, with its number, the words before
    and after it, a bar of where it is, Back / Next and sideways swipes (read from the vault by the entry's id, as
    the password's views). An extended key typed `crypto.xpub` that is private is masked and never copied too, with a
    note saying why: by its prefix (`xprv…`, a typo or not) or by what it holds (byte 45 `00`: a private key behind
    `xpub…`, as BIP-32's test vector 5 writes one; a key passes only when that byte is the kind its prefix says).
    Failing closed, a protected one is not read to know: masked and never copied, without the note. Then the first unprotected receiving address as a label: the network read from the
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
- **Editor** (the card's Edit; a new entry from the `+` next to the search field, a menu: New entry, New note, New
  note from clipboard, §6.4): title, username,
  password (masked, an eye shows it coloured; masked, it shows the card's fixed dots, not its length, and a dot a
  character only while typed in), website, notes, tags, KeePass's expiry (a switch and a day; the time of day kept; switched on with a day already past, as KeePassXC leaves an
  entry that never expired, the day starts a year from today), and the custom fields:
  name above value, each with the row's whole width, and under them the type as a chip (§6.3), a Protected chip
  (a lock, closed when on) and remove; a protected value is masked as the password is, and so is a value the card masks (a
  password-typed one, an `otpauth://` link, a private key). What was revealed is masked again when the app leaves
  (`ON_STOP`), as on the card. The text menu of the
  password, a protected or password-typed value and the lock screen's master password has no items other apps add
  ("Ask Gemini", "Translate", "Read aloud": they would hand it the text); the master password's and a recovery
  phrase's have no Copy or Cut either (a keyboard's Ctrl+C or Copy there copies nothing, and its Cut is refused); the others' Copy and Cut go
  through the app (§4: concealed, cleared after 30 s), never the system's plain copy. **Tags** (Mac SPEC §3.24) are chips with ✕
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
  until tapped. **One-time code…** (keething-spec edit.one-time-code; the Mac's §3.23; *Another one-time code…* once
  the entry has one, or code fields that give none) asks in a dialog for the secret a site shows or its `otpauth://` link, typed (masked, an eye
  shows it) or pasted (*Paste*: the clipboard's text, else the QR codes of its image, a screenshot copied), or its
  QR code from an image file (*Image*: the system's file picker, any image, SVG too, as GitHub gives it) or the camera
  (*Scan*: full screen until a QR code that is a code is seen; another's problem shown, the camera kept on). Of several
  QR codes the first that is a code is taken; none: "No QR code found in the image.": a Base32 secret with spaces, dashes and case ignored, at least
  10 bytes, 6 digits, 30 s, SHA-1; a link with its own; HOTP, Steam and Google Authenticator exports refused with the
  reason, under the field. The first code is the entry's own, a row under Website: its live code and ring as on the
  card, "New: check that the site accepts this code before saving." (or "Changed: …") until saved; the tune button
  shows Digits (6, 7, 8), Period (30 s, 60 s) and Algorithm (SHA-1, SHA-256, SHA-512) as segmented choices (a value
  the entry has outside them is offered too); Undo back to the code the entry had; remove (then "The one-time code
  is removed with the save." with Undo). Saved as KeePassXC writes it, in the entry's own format (§3.3). A further code
  becomes a protected field "One-time code 2" (3, …) holding its link (the title as issuer, the username as account);
  a field holding a code's link shows its live code instead of its value, without a type chip, and is renamed or
  removed but not edited by hand: only a field whose link reads as a code as the editor opens it, or one the dialog
  added. A link typed or pasted into a field stays text while the editor is open; one that gives no code says "Not a
  one-time code: <reason>." under it in the warning's colour, is saved as typed and is masked, here and on the card. An
  entry whose own code cannot be used here (Steam, a counter-based link, a broken secret; the canvas "Keething editor
  codes", agreed 2026-11-20) shows in the code's place the fields it is kept in (`otp`; `TOTP Seed` and `TOTP Settings`;
  KeePass's `TimeOtp-*`) under their own names, not edited, each masked with an eye, and under them the card's reason
  in the secondary colour ("This code cannot be used here: Steam codes."); once their text reads, the live code and
  "Changed: …" instead (they stay text while the editor is open). They are saved as typed, each field's protection
  kept, never overwritten by a new code; remove takes them with the save (Undo back). The Codes view's Add code alike
  puts a code into a field of its own. **Attachments** (keething-spec edit.attachments; the Mac's §3.18), after the fields
  (a note's after its expiry): each attachment a row, its name over its size, NEW for one added or REPLACED for one put
  in another's place, remove at its end; not renamed (the owner, 2026-11-08: a name comes from its file). **Add files**
  opens the system's picker for one file or several (any app's documents, a cloud drive's too), each read off the main
  thread ("Reading the files…", Save waits) and added at the end under its own name; when the entry has that name, a
  dialog asks: **Replace** (the new file in the old one's place, the old one kept by the entry's history) or **Keep
  both** (the new one with a number before the extension, "scan 2.pdf"; Back attaches nothing); a folder ("… is a folder; only
  files can be attached."), a file over 64 MiB (refused before reading when its app says its size, else as soon as more
  has been read: "… is too big (70 MB); attachments of up to 67 MB can be added.", the system's units) and one that
  cannot be read are said under the button, the others added; a large one is asked about first (§3.3, the large
  databases). An attachment without a name or two with one name (only another app's) keep Save off once the
  attachments are changed, said at the row and in the "Save is off" box. A new file's bytes stay with the editor, wiped when it goes
  (Back, a save, a lock); the save writes a copy. The Mac editor's rules (`card/editing`): only what differs
  from the entry as loaded is saved; a field left blank is not saved; a field with a value needs a name, not
  one KeePass uses itself and not one another field has, said at the field and in a "Save is off" box; a new
  entry needs a title or a website. **Group** (keething-spec groups.entry-group, groups.new-groups;
  `plans/done/android-groups.md` G2, G3), at the top for a new and an existing entry: a folder and the path from the top,
  wrapped ("Top level" for the root group, a new entry's to start with), the levels made with the save in their own
  colour with one NEW after the last; under it "“Zed” is made when the entry is saved" ("“A” and “B” are…", from three
  on "3 new groups are…"), or for an existing entry moved "Moves here from Travel › Japan when saved". A tap opens the
  **group picker**, a full-height sheet: "Find or make a group" over one level at a time (the owner, 2026-10-31,
  instead of the whole tree, so a deep group keeps the row's width): the top level first ("Top level", then its
  groups by name), each row with its entries in it and below, "2 groups" when it has groups inside, "Now in …" when
  the entry's group is below it, ⚠ "Another group beside it has this name" for two of a name; a row's tap chooses
  that group (by its id) and closes, its › goes into it (that group first, then its groups; ‹ and its path, cut as
  the list cuts it, above the rows go back up). Typing searches below the level at any depth for a part of a name,
  case and diacritics folded, each found group by its whole path (cut to fit), the part matched bold, those directly
  inside first (`qu`: Qux, Foo › Bar › Qux); a path can be typed with `/` (`Foo / Bar / qu`), its levels any case and
  accent, a group with `/` in its name spanning them. Last, while the levels typed are not all groups, "+ New group
  “qu”" / "+ New groups “Baz › Zed”" "in Foo · made when the entry is saved"; the keyboard's Done takes what is typed
  as it is (a group directly inside of that name, else new groups). Nothing is made until the entry is saved; a level
  another app made meanwhile is taken (§3.3). An existing entry moved goes with the save, alone or with an edit, and
  the snackbar names the empty groups the move deleted. While two or more databases are open, **Save into** (its dot,
  label and folder) sits over the group as one block (another database: its top level, new levels typed dropped), and its sheet ("The group, tags and username follow the database
  chosen.") lists the open ones with a check on the chosen one, "Not open: …" under them (§3.1). Save shows "Saving and checking…"; saved, the editor goes back to the
  card (a new entry's card) at once, which says "Saved and checked" in a snackbar (saved with its file's time too
  close for a sync app, §3.3: a second line "A sync app may not send the file: … Change something and save again.",
  the problem's icon, shown longer; every other screen that saves adds the same line; autofill's save, whose toast's two
  lines would cut it, says it in a dialog until OK, "Saved to Keething" or "Password updated in Keething" over the
  warning: `AutofillSaved`, keething-spec T12 databases C5); not saved, the sheet says
  why, with "Back to editing" (save from autofill: a toast, and back to the app). ✕ or Back with changes asks "Discard the changes?". Not in v1: reordering fields.
- **Password generator** (Mac SPEC §3.20, `core/generator`): a sheet from the wand in the editor's password
  field ("Use this password" puts it in, shown) and from ⋮ › Generate a password in the list's header (a password
  without an entry: Copy, sensitive and cleared after 30 s). Kinds PIN (4–16 digits), Strong (8–64
  characters, A–Z / a–z / 0–9 / symbols each at least once, look-alikes `0 O o 1 l I |` optionally left
  out), Words (2–8 EFF words in Capitals or CAPITALS with exactly the chosen digits and symbols, 0–5 each,
  before, between and after them, a gap holding several or none; two words in CAPITALS never touch, so words
  without a gap are told apart by their case; 0 and 0: the words alone) and Phrase (3–10 lowercase words joined by `-`, space, `.` or `_`); the password
  monospaced with digits and symbols coloured; the recipe's exact bits (weak under 40, strong from 60) and
  length. `SecureRandom`; "at least one of each" and "no two CAPITALS words touching" by drawing again, so
  every valid password is equally likely. The options are remembered (`passwordGenerator` in the app's settings), never a password. The word
  list is a Java resource found by its absolute path: R8 renames the package in a release build (a relative
  path crashed the first phone run).
- **Delete** (the card's ⋮ › Move to the recycle bin): moves the entry to the recycle bin at once, no question; the card
  closes and the list says "Moved “…” to the recycle bin" with Undo (10 s), which restores it. The list
  keeps its scroll position: the row fades out at once (not when the save is done) and the rows below close
  up; a failed save or Undo puts it back in its place. A vault read again (a save, a sync) is listed while
  the one before stays on screen, so no reload empties the list or scrolls it to the top.
- **Delete for good** (⋮ on a card in the bin, a long press on a row in the bin, or ⋮ on any card when the database
  has the bin turned off; keething-spec
  edit.delete-for-good): asked first, "Delete “…” for good?", saying it cannot be undone and that copies syncing with
  the file delete it too; the red Delete for good comes alive after a second, Cancel, Back or a tap outside keep the
  entry. The card closes and the list says "Deleted “…” for good", no Undo; the row leaves at once, a failed save puts
  it back. One entry at a time: there is no Empty recycle bin (the owner, 2026-11-04).
- **Groups & tags** (the folder in the list's header; the Mac's Groups & Tags window, §3.25 there, without the tags'
  and the bin's groups' changes): **a block per open database**, in the window's order, even with one (keething-spec
  `plans/done/android-groups.md` G7). Its header (the database's dot, its label, "38 entries") stays pinned while its block
  scrolls; with two or more open a tap on it folds the block, kept folded while the app runs. In a block:
  **Groups** as segmented rows, "Top level" (the root group) first, then tree order
  (parents first, siblings by name, indented by depth), each with how many entries are in it and below it
  and, when some are in its groups, how many right in it ("3 here"); empty groups too. The counts are that
  database's (the Mac app's settings entry left out). Then the **Recycle bin** with its count, when it has
  entries. **Tags**: every tag outside the bin by name with its count, spellings differing only in case or
  diacritics as one ("also bank, Bänk"). A tap goes back to the list searching for it, as the card's chips
  do: `@Work`, `#bank`, `-` for the bin, "Top level" the whole list; with two or more open the search keeps that
  database only (`!Personal @Work`, `- !Personal` for its bin, `!Personal` for its Top level). Back clears the
  search. The search matches by name prefix at any level (§1), so `@Work` also lists a group "Workshop" or
  another "Work" elsewhere in that database. Under the last block the window's databases not open, "Not open:"
  with each one's dot and label; a tap shows its lock screen, Back comes back here. Each block follows its
  database: read again (a save, a sync) it is built again, locked or closed alone it goes, opened it comes.
  **Changing groups** (keething-spec groups.manage, `plans/done/android-groups.md` G5; `card/editing/GroupChanges`): a long
  press on a group's row opens its sheet: its path, its database and what it holds ("Personal · 12 entries · 3 here",
  "empty"), then **New group inside…**, **Rename…**, **Move to…** ("With everything in it") and, only when it holds no
  entry (the Mac app's settings entry counted) and no group, **Delete** ("It is empty: deleted at once", no question:
  nothing is lost). Top level's sheet has New group… only; the bin's row has none. **New group** is also in the bar (a
  folder with +), at the top level; with two or more open it asks which database first (Save into's sheet, "New
  group"). A name goes in a dialog ("Rename “Work”", "New group in “Work”", "New group at the top level"; Save, Cancel):
  trimmed; the same name closes it without writing; an empty name ("A group needs a name.") or one the parent has in
  any case or accents ("“Work” has a group “Mail” already.", "Top level has …") is refused under the field and the
  dialog stays. **Move to…** opens the group picker titled "Move “Japan” to…" (its path and "12 entries, all go with
  it" under it), existing groups only (no new groups; "Find a group"): a target it may not go to is greyed with why
  ("The group itself", "Inside “Japan”", "It is here now", "“Archive” has a “Japan” already"); Top level is a target,
  the bin never. Each change is saved at once, one at a time, through the checked save: "Saving…" under the row (in
  the dialog, Save waits), then the snackbar "Renamed to “Mail”", "Made “Mail” in “Work”", "Moved “Old” into
  “Archive”" / "to the top level", "Deleted the empty group “Work › Old”"; no Undo (as on the Mac). A save refused
  (the file changed meanwhile: a group gone, a name taken, not empty any more) says why, in the dialog when there is
  one, and the database is read again. Groups go by id, so of two groups of one name in one parent only the one
  pressed changes.
- **Used recovery codes** (a list of codes on the card, an entry outside the bin): copying a code offers **Mark
  as used** in its notice; a long press on a code offers "Mark as used" / "Mark as not used". The mark is
  written into the field's value, in front of the code: `used 2026-10-22 · 1234-5678` ("used" in any case, the
  day in ISO 8601, then ` · `, U+00B7 with a space each side; `card/UsedCode`). So KeePassXC and the Mac show
  it as it is, a field renamed anywhere keeps it, and taking it off gives the code back unchanged. It is saved
  as an ordinary edit of that one field (protected as it was, the old version into the history, SPEC §3.3),
  with a progress ring on the code meanwhile and a notice with Undo after; the Undo puts the value back as it was (a mark
  taken off with the day it had) and offers no further Undo. In a database open read-only (KDBX 3.1, too large to save)
  both ways to mark are refused before anything is saved, as Move to group… is. Only the codes of a list are read
  so; another field with such a text stays text. On the card a used code is "used Oct 22, 2026" alone while the
  codes are hidden, and struck through above that line when they are shown; the list's title says "3 of 4
  left" (left: the codes not marked used that have a value; in the error colour when none are); copying a used
  code copies the code alone. Under the title a bar of one segment per code, on one line however many (thinner for
  more, used and empty ones empty, in the error colour when two or fewer are left), says how many are left; the next unused code is outlined
  and **Copy next unused · 4** copies it (its notice offers Mark as used, as any code's does). The editor shows
  the value as it is stored. The Mac app's side:
  `~/Projects/keepass-bar/HANDOVER-used-recovery-codes.md`.
- **History** (the card's ⋮, for an entry in the bin too; Mac SPEC §3.22):
  "Reading the file…", then the versions newest first, each dated by its modification time, the current one
  marked, each with what it changed from the one before (`card/history`, the Mac's `EntryVersionDiff`):
  title, username, password, URL, notes (masked when either version protects them; a change of their protection
  alone, "not protected" → "protected", its own line), the kind (§6.4: "Kind" added / removed), custom fields
  (added / removed / changed, a change of protection or of type, the order), tags, expiry, the one-time code or the
  fields it is kept in when they give none (that it changed, never its values), attachments
  (added / removed / size). Old values struck through above the new; masked as the card masks them in either version
  (protected, typed `password` or `wifi.password`, a card's number, CVC or PIN, a recovery phrase or passphrase, an
  `otpauth://` link, a private key, a private extended key in an `xpub` field), an eye each; all masked again when the app leaves.
  The oldest version kept has nothing to compare with; "No earlier versions are kept" when there are none.
  The versions are wiped when the screen goes (a lock closes it); the values compared are plain strings
  while it is open, as in the editor. **Go back to this version…** (keething-spec edit.history-restore; the canvas
  "Restoring a version", agreed 2026-11-08: going back to a whole version, as KeePass does; one field's value taken back
  is the idea edit.history-field-restore) is a tonal button at the end of every version's card but the current one;
  an entry in the recycle bin ("In the recycle bin: restore the entry first to go back to one of its versions.") or a
  read-only database ("… its versions are shown, not restored.") has none, said on top. It asks once: "Go back to this
  version?", "<title> becomes as it was on <date>, with everything changed since undone. The current version goes into
  the history, so you can come back to it the same way.", then **What changes**: the current entry compared with that
  version as the cards compare (protected values masked, an eye each), and when the history is full what it then drops
  ("This database keeps 3 earlier versions, so the oldest, from <date>, goes."; the version restored itself among them
  "leaves the history as it becomes the current one"). Go back saves it (§3.3), the button "Going back…" and the
  others off meanwhile; done, the card again with "Back to the version of <date>"; a failure is said on top ("Not
  restored: …") and the versions read again.
- **Recycle bin**: `-` in the search; a card in the bin has Restore instead of edit, and Delete for good… under ⋮
  ("Restored to Web & Mail"); a bin row's long press offers both too. Its rows and its search read the paths inside
  the bin, the levels down to the bin's own group left out (a bin KeePass moved under another group too).
- **Settings**: **Databases**: every database (tile, label, its folder or "Not found · Download", "No access · Sync/Work",
  "Needs its folder · Sync/KeePass" in red, a key for
  one with a key file, a fingerprint for one the fingerprint opens); a tap opens **its page**: tile, label, path from
  the shared storage's root, "Changed 2 hours ago" (of a file not found "Not found at …", or without access or kept by
  its path the lock screen's words, with Locate… or Choose its folder…; not found with a backup that would open, "Family.kdbx
  is not in Sync/KeePass any more. Locate the file, or restore its newest backup, of today, 14:03." with **Restore
  backup…**, which asks its restore in Backups, beside Locate…; keething-spec databases.not-open AC5; the canvas "Backups
  on Remove and Not found", https://claude.ai/artifact/LV2FpzThNAdfj8FStc9aeb, agreed 2026-11-19; and "Last opened
  2 weeks ago" when it was), **Name and colour** and **Master key** (below; agreed on the canvas "Database name, colour
  and master key", 2026-11-08), **Key file** (its name and folder, or "None · It opens with its password alone"; Choose… /
  Change…, Forget; "Remembered here by where it is, not its contents: beside the database, or a file chosen alone. It
  must be there at every unlock, the fingerprint too."), **Fingerprint** (Use fingerprint and what it does now, §5; it says where the key is: StrongBox, the TEE),
  **Backups** (below), **Remove from list…** ("Forgets it on this phone, with its stored password and its backups. The
  file stays."), asked first: "Remove “Family” from the list?", "Keething forgets it on this phone, with its stored
  password and its 7 backups. The file stays where it is." ("…and its backup.", none: "…with its stored password. The
  file stays where it is."), its file not found and backups kept: "Family.kdbx is not in Sync/KeePass any more, so its 7
  backups on this phone may be its last copies. They are deleted with its stored password, and can’t be brought back."
  and "To keep it, restore its newest backup first.", Remove in red; the same question from the lock screen's sheet and
  the chooser (keething-spec databases.list AC6). Each acts at once.
  **Backups** (keething-spec databases.backups-window AC1-AC9; the canvas "Database backups", agreed 2026-11-12), open
  or locked alike: the switch **Back up before changes** (the record's `backupsOn`, on by default), while it is on
  **Keep** 5 · 10 · 20 · 50 (`backupsKept`, 10 by default), a line ("7 kept, 8.1 MB: the file as it was before the first
  change of each unlock.", "None yet: the first is kept before Family’s first change after an unlock, the file as it
  was.", "Off: nothing is copied before a change. The 7 kept stay until deleted.") and "On this phone only, apart from
  other databases’: removing Keething, or its data, removes them." A number below how many there are asks first
  ("Keep 5 backups?", "The 2 oldest backups of Family are deleted from this phone now. They can’t be brought back.",
  Cancel / Delete 2) and deletes them at once; a larger one asks nothing. Turning it off with backups kept asks "Stop
  backing up Family?" ("Family is no longer copied before its changes. Its 7 backups can stay here or go.", Cancel /
  Keep them / Delete them). Then the backups, newest first, by when each was written, read with no key: each its date
  ("Today, 14:03", "Yesterday, 21:40", "Mon 9 Nov, 08:15"), how long ago ("17 hours ago", "3 days ago" by the calendar)
  and size, the file before a restore marked **Before restoring** beside its date; one whose header does not read or
  does not match its own SHA-256 (KDBX 4: `KdbxReader.isHeaderDamaged`; a KDBX 3.1 header has none, only its signature
  is read) says "damaged: it would not open" in red. The newest three,
  then **Show all N** / **Show fewer** in place; **Delete all backups…** under them ("Delete all backups?", "The 7
  backups of Family are deleted from this phone. They can’t be brought back.", "Backups made before a master key
  change still open with the old one: this is how they go.", Cancel / Delete all). A backup opens a sheet: its date,
  how long ago and size, **Restore this backup…** ("Family becomes as it was then"), **Delete…** ("Delete this
  backup?", "The backup of Mon 9 Nov, 08:15 is deleted from this phone. It can’t be brought back.", Cancel / Delete;
  done, "Deleted the backup of Mon 9 Nov, 08:15" at the bottom) and Close. In place of Restore, a damaged one says "Its
  file is damaged: its header does not match its checksum, so it would not open. It cannot be restored.", and a
  read-only database (§3.3) "Family is read only on this phone: its backups are listed and deleted, not restored.",
  which the section says too. Restore asks once ("Restore this backup?", "Family becomes as it was on Mon 9 Nov, 08:15.
  Changes made since leave the database, here and on every device it syncs with.", "The file as it is now is kept as
  a backup first, so the restore can be undone.", Cancel / Restore; "as it was today, 14:03" for one of today or
  yesterday), then restores it (§3.3 "A backup restored") on the
  app's scope, "Restoring the backup of Mon 9 Nov, 08:15…" in the section meanwhile and Restore off. Done, "Restored
  the backup of Mon 9 Nov, 08:15" at the bottom with **Undo**, which restores the file before the restore the same way
  ("Back to Family as it was before the restore"); the page's header then shows the file's new time. A backup that does not open with the key Family was open with
  locks it, the section saying "Restored. This backup opens with the master key Family had on Sun 1 Nov, 10:20, so
  Family is locked: unlock it with that password. Its fingerprint stays off until then." A failure is said in the
  section ("Not restored: Family.kdbx could not be written (…). Nothing was changed, and no backup was added."). Off,
  the backups kept are listed, restored and deleted all the same. **Its file not found** (§3.3 "A backup restored"),
  each Restore asks "Restore Family from its backup?", "Family.kdbx is written again in Sync/KeePass, as it was today,
  14:03. Changes made after that are not in it." and "There is no file to keep first, so this restore has no Undo. If
  the file was only renamed, or is not synced yet, Locate… finds it instead."; done, "Restored the backup of …" with no
  Undo, and in the section "Restored: Family.kdbx is back in Sync/KeePass, as it was today, 14:03. It opens with the
  master key it had then."; not done, "Not restored: there is a Family.kdbx in Sync/KeePass again. Nothing was written:
  unlock it as it is." or "Not restored: Sync/KeePass is not there any more. Nothing was written: locate the file, or
  choose its folder again." The same section shows in a sheet from the line's sheet and the chooser (Restore its newest
  backup…, its question asked at once; Other backups…; `BackupsSheet`).
  **Name and colour** (keething-spec databases.rename-recolour): while the database is open and can be changed, the name
  in a field (the file's `Meta/DatabaseName` as written; under it "Its file stays Work.kdbx.", "Empty: it is called by
  its file name, Work." or "“Passwords” counts as no name: …"), the palette's nine colours and None (a dashed ring) in two
  rows of five, the file's colour chosen as its nearest ("Its file's own colour, shown as the nearest of these; it stays
  as it is until another is chosen."), and Save: only what changed is written, as a change of the file (§3.3: backed up,
  checked): a name with `DatabaseNameChanged`, a colour as its light dot `#RRGGBB` (none: emptied) with
  `SettingsChanged`; the root group keeps its name. "Saved into Work.kdbx", or why not; the labels and colours follow at
  once (§3.1). Locked: "Unlock it to change its name, colour or master key: they are written into its file."; read only,
  why. **Master key**: "Password", "Password and key file" or "Key file alone", "Changed 14 months ago" (the file's
  `MasterKeyChanged`, while open), **Change master key…** while it is open and can be changed, and "What opens it on
  every device. After a leak, change it here: the others then ask for the new one."
  Under the list **Open a database…** (it becomes current; one open stays open, §3.1) and **New database…** (§6.2). Then **Quick notes**: "Quick notes go to", Current database or a database of the
  list with its dot, in a menu, and Add the Quick note tile (§6.4). Then the app's fingerprint switch (§5) and,
  while it is on, how often to ask for the master password (7, 14, 30 days, Never; §5),
  lock timeout, the **Theme** (System, Light, Dark; keething-spec app.theme-choice: the app's own night mode, kept by the
  system for every window of the app, told again at each start), the **Language** (the app's language as Android keeps it
  for the app: System, English or Русский; the row opens the system's page that changes it; keething-spec
  app.language-setting), how long a sharing request and a sending's pictures last (1, 3 or 7 days, §10); the last key derivation and the memory used, to check them on the phone; the storage probe
  in a debug build (the fingerprint spike, and what the storage provider does with names in a folder granted there).
  Last **About**: "Keething for Android 1.0.0 · build 1.", the font's licence, About Keething and Report a problem.
- **Change master key** (keething-spec databases.change-master-key; agreed on the canvas "Database name, colour and
  master key", 2026-11-08), from a database's page, over whatever was shown, kept by the app as New database is (a
  change goes on when the screen is gone; a lock takes the form, with what was typed, unless the change is being
  written: that one goes on for the lock's 10 s, and stops before the replace only when they run out, §3.3, §4). First the key it is open with: "Current master password" (the eye; nothing
  typed with a key file: the key file alone), "With its key file, Work.keyx in Keys", Continue; it is compared with
  the session's key, the key file read again, nothing derived. Not that key: "That is not its master password with
  Work.keyx. Nothing changed." and nothing more. Then "Its current key, confirmed", **Password**: Keep it / New / None
  (None only while a key file stays; New: New master password and Again, the strength line and Suggest a passphrase
  as New database's), **Key file**: Keep Work.keyx / Make a new one… (a folder picker, Documents first; `<Name>.keyx`,
  `<Name> 2.keyx` when that is taken, never over a file; warned beside the database and in a folder that syncs, "Lose
  the key file and the database never opens again. Keep a copy apart from it.") / Use one I have… / Remove it (None for
  a database without). "Every device that opens Work.kdbx will need the new key. The file is backed up first; if
  anything fails, it stays as it is." **Change master key** writes the file with the new key as one change (§3.3,
  "A new master key"); then "Master key changed", what it opens with now, that other devices ask for the new key and a
  fingerprint stored there stops working, and "Copies of the file made before still open with the old key: the backups
  on this phone and elsewhere, other devices' copies, a sync's old versions (Syncthing's .stversions). After a leak,
  delete those you can.", Done; a lock that came while the file was replaced (it is saved all the same, §3.3) adds
  "It locked while the change was written; the change is in the file. Unlock it with the new key.", the form then
  staying through the next lock until Done (nothing typed is left in it). Not changed: why,
  the form as it was; a key file made for it is removed again.
- **About** (keething-spec app.about; the owner, 2026-11-02): the logo, "Keething", "for Android", then Version "1.0.0 ·
  build 1" (the version code said as a build, the owner), Built "2 November 2026, 20:06" (the phone's time zone) and Commit "becc85f", or "becc85f with uncommitted
  changes", selectable for a bug report; Back returns to what was shown. Reached with every database locked: an ⓘ in
  the top corner of the welcome, the chooser and a lock screen (not in the chooser's run, nor over another app), and
  About Keething in Settings. It covers the app as New database does. The facts are the asset `build-info.properties`,
  written by Gradle at every build (`app/build.gradle.kts`, `BuildInfo`): not BuildConfig, so nothing compiled changes
  with the time and the configuration cache keeps no time of its own; the time is SOURCE_DATE_EPOCH's when it is set,
  else the commit's when nothing is uncommitted (the Gradle wrapper's jar, which F-Droid's scanner deletes, not
  counted), else the build's: the same commit builds the same APK here and on F-Droid (keething-spec app.fdroid-build). Under them Licence "GPL-3.0-or-later" (a tap shows its text, the
  asset `LICENSE.txt` Gradle copies from the repository's LICENSE) and Source "github.com/cotton-more/keething-android"
  (a tap opens it), and Security "How to report a vulnerability" (a tap shows SECURITY.md, the repository's, copied by
  Gradle into the asset `SECURITY.md` as LICENSE is; keething-spec app.security-policy); then **Third-party licences**: each part in the app with what it is for, its authors and its
  licence (the EFF word list's credit said there, as CC BY asks), a tap showing the text of `assets/licenses`
  (`THIRD_PARTY`; a test keeps the list and the folder the same; the build keeps the list true, keething-spec
  app.dependency-licences: the release's libraries by Licensee, the files copied into the source by
  `app/third-party-files.txt`). Then **Report a problem** (also in Settings' About;
  plans/android-release-1.0.md T5): the browser opens a new issue of `github.com/cotton-more/keething-android` whose
  body says first, in a comment the form shows while it is written, that a security problem goes to SECURITY.md, then asks what happened, what was expected and the steps, then says the version, the commit, Android (and its API
  level) and the phone's maker and model (`ProblemReport`). Nothing from a database, nothing sent by the app (it has no
  network): the person writes and sends the issue. Crashes: the system's dialog for now.
- **Launcher shortcuts** (long press on the icon; static, `res/xml/shortcuts.xml`): "Codes", then "Note from
  clipboard" (a quick note, §6.4). Codes: unlock, then the Codes view. Their icons are adaptive: the glyph (the ring;
  the clipboard) large in the wallpaper's accent on a white plate, the launcher's own for any shortcut, which stays
  white in the dark theme (seen on the Pixel 6); a transparent plate is drawn black by the Pixel's launcher. A
  monochrome layer for themed icons.
- **Codes view** (keething-spec app.codes-view; `CodeTiles`): titled "Codes", "<database> · N codes" under it, a tile
  per one-time code as an authenticator app lists them: each entry's own code and each further one (a custom field
  holding an `otpauth://totp/` link), an entry with further codes only too, a code that cannot be read not; by the
  entry's title, its own code first, its further ones in field order (`VaultListing.codes`). A tile: the entry's icon
  and title, under it the username (own code) or the field's name with a field icon (further code), under that
  the entry's group line as its row in the list has it (the path behind a folder, nothing for the root group; with two
  or more databases open "● <database> › <path>"), then the live code large with the next one beside it (card.code-timer). On a phone the view stays upright (either way up; `HoldPortrait`), sideways only spreading the tiles; a tablet or an unfolded foldable turns as it is held. A tap anywhere on it copies the code as any code copy (the
  notice names a further code by its field); ⋯ opens a sheet with the code, Copy code, Open entry, Edit code… and
  Delete from the entry. **Edit code** (`EditCodeSheet`, `CodeEdits.edit`): the code live as it would be, a further
  code's name (checked as any field's), the secret kept or replaced (Camera, Image, Paste or typed, read as the editor
  reads one; a link brings its own digits, period and algorithm), digits, period and algorithm; the own code saved as
  the editor saves it (in the entry's format), a further one's link into its field (its protection and type kept); the
  rest of the entry untouched, the old version into its history. **Delete from the entry** says under it what goes
  ("The field “One-time code 2” goes from Microsoft", "Its one-time code goes from GitHub"): only that code's fields, no
  further code taking the own code's place; the notice says it again ("“One-time code 2” deleted from Microsoft", "One-time
  code deleted from GitHub") with Undo, which writes the fields back as the file held them (`Entry.codeFields`,
  `TotpChange.Fields`: KeePass's `TimeOtp-*` in their own encoding, not written again in the database's format), a
  further one where it stood. A code that is all its entry holds but the title (no username, password, URL, notes,
  attachments, other field or code): **Move entry to the recycle bin** instead, as the card's Delete, with Undo.
  **＋** beside the search field adds a code (`AddCodeSheet`): Camera, Image, Paste, or typed; then the code live, the
  database ("Into", only while two or more take entries; the start as a new entry's), and, when that database has an
  entry of the link's issuer as title (folded as the search folds), "Add to “X”" first (its own code when it has none,
  else the next "One-time code N", protected) or "A new entry" (title and username from the link's issuer and account,
  in the root group); nothing written until Add code. Two columns from
  840 dp. "Search codes" searches the tiles: the title, the username and a further code's field name, nothing else of
  the entry (not its URL, notes or other fields), the filters (`@`, `#`, `+`, `!`) as in the list. Reached from the
  launcher shortcut and from the Codes button in the list's header (icon A, the ring, before Sharing); Back (after
  clearing the search) or the arrow goes to the whole list with nothing typed; a lock keeps the view, so the shortcut
  opens the lock screen and then it. A group or tag chip on a card leaves it.


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
  `wifi.password`, `wifi.security`), which make the card's payment card and Wi-Fi views; a bank account's (`bank.iban`,
  `bank.bic`, `bank.name`, `bank.holder`, `bank.account` where there is no IBAN, `bank.code` for a national bank
  code such as a БИК or a routing number, `bank.corr` for a correspondent account), an identity document's
  (`doc.type`, `doc.number`, `doc.name`, `doc.country`, `doc.birth`, `doc.issued` dates, `doc.expiry` a date
  something ends on like `until`), an SSH login's (`ssh.host`, `ssh.port`, `ssh.user`) and a crypto wallet's
  (`crypto.seed` the recovery phrase, `crypto.passphrase`, `crypto.address` a receiving address, several allowed,
  `crypto.xpub` an extended public key, `crypto.path` the derivation path). Type names ignore
  case and outer spaces; field names match exactly. A type this version does not know is kept (the editor
  too): it is read as none, and the offer below leaves its field alone (a later version typed it).
- **A field without a type** is read by its value (unmistakable dates, http(s) links, numbered codes, and an IBAN:
  the right country, length and mod-97 check, shown in groups of four with its country's flag and copied without
  spaces; the editor names it "IBAN · auto"; it makes no face without the types). The
  rules the settings entry held before (`<field name> = <type>` lines) are no longer read: the Mac's field
  report moves them into the entries. Protected fields are never read, typed or not.
- **The offer to type a card, a network, an account, a document, an SSH login or a crypto wallet** (`TypeSuggestion`,
  the Mac's rules alike): fields without a type named as a payment card's parts (a number — 12 to 19 digits when unprotected, a protected one by its name — and at least an
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
  beside a phrase and a derivation path, "Looks like a crypto wallet · Show as wallet"). The tap saves the types (an edit of the field types only, §3.3), so the face appears, here and on the
  Mac; nothing is typed without it. "Not now" is remembered per entry (its ref in the app's settings, as Recent keeps
  refs, §3.1); not offered in the recycle bin, nor while the database is open read-only, nor for fields with any
  type stored.
- **The editor**: the type chip under a field's value names the type it has ("Card expiry ▾"), or what its
  value is read as ("Text · auto ▾"), or a type not known here ("“x.y” ▾"); its menu: *Automatic (…)* (no type; what it is read as in brackets), Text, Date, Date until
  (expiry), URL, Code, Password, *Card ›*, *Wi-Fi ›*, *Bank account ›*, *Document ›*, *SSH ›*, *Crypto ›*, and a type not known
  here, kept, shown disabled. A type
  given to a field without a name names it. A field typed `date`, `until` or `card.expiry` and not protected
  has a calendar (the day written `2027-10-31`, a card's expiry as its month `2027-10`); a value that is no
  date says so under it ("Not a date, shown as text…") and is saved all the same; likewise a recovery phrase that
  fails its check (read here even when protected: the form holds its text anyway), an address or extended key whose
  checksum fails, a private key typed as the extended key, a path that is none, and an unprotected SSH host not
  written as one ("Not a host name, shown as a row. Write it as host, user@host or host:port."; only how it is
  written, nothing is looked up or connected) or port outside 1–65535 ("Not a port (1–65535), shown as a row."). The types saved are every
  field's that has one, but `text` only where the value would be read as something else; a rename carries
  the type along; types of fields the entry no longer has go with the next save, which they do not cause.
  A card number, CVC or PIN gets the number keyboard, an SSH port the digits.

### 6.4 Notes and quick notes

Built and reviewed (keething-spec `types.note`, `app.quick-note`, `app.quick-note-source`; `plans/done/android-notes.md`,
the mock-ups on https://claude.ai/artifact/CopWwuG57cu4v8iY6Lnzr9): what a note is, in the file (AN1); the card, the
list, the search and the history (AN2); a note sent whole (§10); the editor, New note and New note from clipboard
(AN3); where a quick note goes, its editor, its run and the setting (AQ1); Keething note in the share sheet and the
selection menu (AQ2); where a quick note came from (AQ3); the Quick note tile and the launcher's Note from clipboard
(AQ4); an independent review, KeePassXC and the Mac on one file by hand (AN4). Not yet: a quick note's Database row
(app.quick-note AC2, databases.one-list AC5, AC7). A note's editor has attachments since edit.attachments (F18).

**What a note is** (`core/domain` `Entry.isNote`, `EntryType`; the Mac's, SPEC §3.27 there). An entry's kind is one
item of its `CustomData`, `Keething.EntryType`, beside the field types' (§6.3): after `AutoType`, before `History`,
other apps' items kept, its `LastModificationTime` kept up to date. It is read ignoring case and outer spaces and kept
as stored (`Entry.entryType`). An entry is a note when its stored kind is `note`, whatever else it has; another kind
makes no note and stays as it is; without one, when its Notes are not blank, its username, password and URL are blank
(white space only counts as blank), and it has no custom fields and no field of a one-time code, even one that makes
no code (`Entry.hasCodeFields`); attachments are allowed. An entry read as a note so is not written to. The settings
entry is never a note (it is not among the entries). A note's text is its Notes, protected by default
(`Entry.notesProtected`; §3.3 keeps the protection through an edit).

**Its title** when saved without one (`NoteTitle.make`): the text's first line that is not blank, outer spaces trimmed,
cut to 60 characters (grapheme clusters: an emoji or a letter with its accent is one) then `…`; for a protected text,
or one with no line to take, "Note, <when it was made>" (`Times/CreationTime`, the date with the month's short name and
the time to the minute in the phone's region: `NoteDates`, ICU's `yMMMdjmm`, given by the app), plain "Note" without
it. A text received (the clipboard, a quick note) loses its blank lines before the first line and after the last
(`NoteText.trimmingOuterBlankLines`).

**On the card** (`core/card` `EntryCardBuilder`, `app` `NoteTextView`; the Mac's `ProtectedTextWell`): a note's text is
the first section, labelled "Note", larger than a field's value (19 sp), its line breaks kept; everything the note has
besides comes under it (a website, a password from KeePassXC, a kind's face, custom fields, attachments), and Notes
are not repeated. A protected text (a note's, or an ordinary entry's Notes protected in the file) sits in a well
recessed into its row: hidden, one line with a lock and "Hidden", the same whatever the text, the text itself not
composed (nothing of it on screen or in the accessibility tree); the eye beside the label is the one control that
shows and hides it; shown, the well opens to the text's height (a note) or to seven lines scrolled inside (an
ordinary entry's Notes). It is masked again as every concealed value (another entry, the card left, a lock: the
reveal state goes with the card). Copy copies the whole text, protected or not, hidden or shown, through the app
(§4; marked sensitive when protected). A note's text and protected Notes, once shown, can be selected: the menu has
Copy and Select all only (no other apps' items, `SecretTextMenu`), and a selection goes through the app too
(`EntryCard.selectionToCopy`: only a part of a text shown in clear; "Copied · clears in 30 s"); Compose's selection
writes through the clipboard the well gives it, so the system's plain copy is never reached. Compose's smart
selection is off app-wide (`KeethingApplication`): it would hand a selected text to the system's text classifier,
another process, on a long press. Unprotected Notes of an
ordinary entry keep the system's selection, as before. The header's icon is the note tile (a note on a warm tile).

**In the list** (`EntryFace.Note`, `NoteLine`): the note tile in place of the entry's icon or a kind's face, and as its
line the text's first line that is not blank and not the title (both trimmed; a title made from that line counts as
it), never the username or the site; nothing when there is none; for a protected text a lock and "Protected note",
decided before the text is looked at. A protected text is not searched (§6.2), every entry's protected Notes with it.

**The editor** (`core/card` `EntryEditor` with `EditorLayout.NOTE`, `app` `EntryEditorScreen`; the Mac's
`EntryEditorModel` `.note`). The `+` next to the search field is a menu (§6.2): New entry, New note, New note from
clipboard ("Opens it with the copied text"). New note opens the editor of a new note on its text, the keyboard up, in
the database and group a new entry would go to (§3.1, Save into while two or more are open), with no username;
"New note" / "Edit note". An entry that is a note (its kind, or its fields alone) opens in the same layout: the group,
Title, then the text and **Protect text** under it in one block ("Hidden on the card until shown, and not searched"),
the website when it has one or once **+ Website** is tapped, tags, the expiry. What another app gave the note (a
username, a password, custom fields, a one-time code) shows and stays, editable; nothing of that is offered (no Add
field). Attachments are the editor's as in every layout, after the expiry (keething-spec edit.attachments). A new note's text is
protected; the switch is written whatever `ProtectNotes` says, an existing note's only when changed (one read as a
note by its fields keeps the protection it had until switched). Saving marks the entry a note when it has no kind;
opening and closing writes nothing; a kind this version does not know is never written over (an entry with one opens
as an ordinary entry). A note needs its text (a new one: the text, never the title alone; an existing one: a text or
a title). An empty title is written at save as the title above says, its placeholder saying what it will be ("Title:
Note, 31 Oct 2026, 20:14", "Title: <the first line>", "Title: the first line" while there is no text); an untitled
note's time is when it was made (its `CreationTime`, a new one's when its editor opened), in the phone's format
(`PhoneNoteDates`: ICU's `yMMMdjmm` in the phone's locale). Protect text switched on empties a title made from the
text's first line (as saved or as typed, cut or not), so the line does not stay in the unprotected Title; a title the
user typed stays. The text is shown as typed in the editor (the window is never captured, §4); while protected its
text menu has no other apps' items and its Copy and Cut go through the app (§4), as every secret field's in the
editor does (the password, a protected value, backup codes being added, an entry's Notes protected in the file; not a
new entry's Notes, which the database's `ProtectNotes` may protect only at save). Switching Protect text keeps the
cursor and the keyboard. A username or password given to a note's editor from outside (autofill's save offer updating
a note with a website) shows, so it is never saved unseen, and stays while edited. The clipboard's text is a clip's plain text, else its HTML
as text; a link or a file alone is no text, and a URI's content is never opened.

**New note from clipboard** (keething-spec app.quick-note AC6, AC7): the tap reads the clipboard's text once (the app
is in front, so Android lets it; Android 12+ says "Keething pasted from your clipboard" when another app copied it),
blank lines before the first line and after the last taken off; the clipboard is not written or cleared. No text (or
only blank lines, a picture, a file): "No text on the clipboard" over the list, and no editor. Otherwise New note's
editor with the text in it, already a change (Back asks before discarding). The text lives only in the editor: it
travels to the editor's screen in memory (`NewNote`, whose name says nothing of it, since a screen's name keys its
saved state), not in saved state, a draft, a log or the settings, and goes with the editor (Back, a save, a lock). It gets no tag and no
Website (app.quick-note-source AC6): the app is Keething itself, and no browser's copy carries the page (a copied link
is the text itself).

**Where a quick note goes** (keething-spec app.quick-note AC1, AC2; the Mac's §3.28 there). Settings, Quick notes:
"Quick notes go to" is Current database (the default; "Personal now" under it) or one database of the list, each with
its dot (`QuickNoteDestination`). `AppSettings.quickNoteDatabase` stores that database's id, nothing else, under
`quickNoteDatabase`, removed when Current database is chosen. `QuickNoteTarget.resolve` gives the database set while it
is in the list, else the current one (the lock screen's), else the list's first; one set and since removed from the
list stays set, Settings saying "The database chosen before is no longer in the list: quick notes go to the current
one." until another is chosen. In that database the note goes to the group path `Inbox`, found as a typed path is
(`GroupTree.resolve`: the first top-level group of the name in any case, accents and outer spaces aside; a deeper
Inbox is not it; the recycle bin never), else a new top-level group made with the note at Save (the group row shows
it new). The saver meets the file as it is then (`GroupLifecycle.resolving`, groups.new-groups AC3): an Inbox another
app made since the read is taken, never a second one.

**A quick note's editor** (`EntryEditor.createQuickNote`, `NewNote(text, quick = true)`; app.quick-note AC3): New
note's editor (protected, the kind written at Save, no username, the dated title) with the text given and the group
above; the text is in the draft, not the start, so it is a change and Back asks first. The group can be changed. No
Save into: the setting chose the database.

**A quick note's run** (`app/quicknote` `QuickNoteRun`; app.quick-note AC3, AC4, AC7; the Mac's `QuickNoteRun`): one
request, from a text handed over (a share or the selection menu, `Source.Given`) or the clipboard (the tile and the
launcher's shortcut, `Source.Clipboard`). No text or only blank lines: "No text to save" (handed over) or "No text on
the clipboard", and no editor; no database in the list: "No database to put the quick note in". The target open: the
text, outer blank lines trimmed, goes to the editor at once. Locked: the run waits for that database's unlock (another
database unlocked changes nothing; a second request while it waits changes nothing); from the clipboard only its
description is looked at until then (`SystemClipboard.hasText`, which shows no "pasted" notice), its text read once
the database is open, as it is then. A text handed over is in memory already and is held only until the unlock or
giving up (§4). Given up (the lock screen left): nothing read, nothing kept, and a later unlock opens nothing. Once
the editor has the text the run keeps none of it. Keething note (below) starts it with a text handed over; the Quick
note tile and Note from clipboard (below) with the clipboard.

**Keething note** (`app/quicknote` `QuickNoteActivity`, `QuickNoteModel`, `QuickNoteIntent`; app.quick-note AC3, AC4,
AC7; the Mac's `QuickNoteWindow`): Keething's target in other apps' share sheet for a text (`ACTION_SEND`,
`text/plain`), beside "Read with Keething" (§10.3), and its item in the text selection menu (`ACTION_PROCESS_TEXT`,
under ⋮ in Chrome's and Firefox's toolbar). The selection menu gets no result back, so a selected text in an editable
field stays as it was. The text is the share's `EXTRA_TEXT` or the selection's `EXTRA_PROCESS_TEXT`, a page shared
giving its title or address instead (below); a share's subject is used only as a page's title, so Chrome's "Including
link: …" over a selection and Firefox's "Share via" are left out. No text: "No text to save", and nothing opens.
The screen is a sheet over the other app (a see-through window, the sheet 24 dp below the status bar, the other app
dimmed behind it; the agreed mock-up). The quick notes' database open: the quick note's editor ("Quick note", Save).
Locked: its lock screen under the title "Keething note", with "Unlock to write the quick note" under the database: the
password, the key file, Locate…, and the fingerprint asked for once, when it shows (the system's prompt is a window
over the sheet and does not stop it); no other database is chosen on it;
a wrong password keeps it; unlocked, the editor. That database in the list but closed (not in the window) is put into
the window for its lock screen, and stays open there after. Saved: "Saved to <group>" (the group's name, never the
title or the text), the screen goes and the other app is in front again. The editor is a quick note's (above) without
places: no Save into, and the next new entry's database (`lastNewEntryDatabase`) stays as it was. An editor open in
the main app is left alone.
Given up, nothing is saved and nothing of the text is kept: Close, or Back (in the editor Back asks first, as New
note's does); the screen stopped by anything but what it opened itself (Home, another app, Recents, the screen off: a
key file picker or Locate…'s folder picker it opened leave it waiting); a lock of the app (Lock all, the timeout, a
stay away past the timeout, which a frozen app counts when it is back in front: `AppLocks.counted`; while it waits
behind a screen it opened, the lock alarm wakes the app at the timeout, nothing open to lock too, so Home from a picker
does not keep the text until Keething is next opened: `Sessions.quickNoteAwaited`); its database
locked while the editor is open, or leaving the window; a newer Keething note, which replaces it; a screen re-created
after its process died, which does not read its intent again. Each share opens the screen in a task of its own
(`documentLaunchMode="always"`: with one task, a second share brought the first one's task back and its text was
dropped); the selection menu, which Chrome and text fields start for a result, opens a new one in the caller's task
(Android makes no document task for a launch that asks for a result; Firefox asks for none, so its gets a task of its
own). A share's task is never in Recents (`excludeFromRecents`), none is kept across a restart (`persistNever`),
`FLAG_SECURE`.
The run starts once the screen is resumed, after the app's own start, where a stay away past the timeout locks first
(§4): a database the timeout should have locked never opens its editor for a moment, and that lock is not counted
against the quick note. Whether its database is open is the sessions' word: the window's flow follows them a moment
late and can still say open just after a lock (seen on the Pixel 6, the timeout's lock as the sheet opened), which
opens nothing. Recents keeps the sheet as its live top card (blank: `FLAG_SECURE`); picking another app's card gives
it up. While a screen it opened is in front, an unlock elsewhere (the main app) still opens the
editor behind it; a lock of the app then gives it up. The log says which screen showed, by name ("Unlock", "Editor", "Gone with a notice"), never the
text, the database or the group.

**The Quick note tile and Note from clipboard** (`app/quicknote` `QuickNoteTileService`, `QuickNoteTile`,
`res/xml/shortcuts.xml`; app.quick-note AC3, AC4, AC7): a quick note from the clipboard's text, copied earlier or in
an app that offers no Keething note (Telegram shares no message to other apps). The Quick Settings tile **Quick note**
("Keething · from the clipboard" under it; an action, never shown as on) and the launcher's second shortcut **Note from
clipboard** (long press on the icon, under Codes) open Keething note's screen (above) with the clipboard's action
(`QuickNoteIntent.ACTION_FROM_CLIPBOARD`); everything above holds for it: the sheet, the lock screen, saved, given up.
The tile collapses the shade and opens it (`startActivityAndCollapse`); on the phone's lock screen Android asks the
phone's unlock first. Only the app with the window focus may read the clipboard (Android 10+: before, even resumed,
it says the clipboard holds nothing), so the run starts once the screen has the focus (`onWindowFocusChanged`), not at
`onResume`: a screen stopped before it gives up having looked at nothing. Then the clip's description alone: no text
(nothing, a picture, a file) says "No text on the clipboard" and asks no unlock, the database open or locked. Locked:
its lock screen, the text not read; unlocked while the fingerprint prompt (a window of the system's) still has the
focus, the editor waits for the focus to come back and reads the text then, once (one "Keething pasted from your
clipboard" per note). The intent of the clipboard's action is not read (any app may start it: it can only open the
editor on the user's own clipboard). No tag and no page: the clipboard says neither. **Settings → Quick notes → Add the
Quick note tile** asks Android to add it (`StatusBarManager.requestAddTileService`, Android's own question); "The Quick
note tile is in Quick Settings already" when it is, and "Android did not ask. Add the tile from Quick Settings' edit
button." when Android answers with an error (the app not in front, a question already showing). Added or not added
by the user, Android's question said it; Android may also answer "not added" without asking after the user said no
several times, and then nothing is said.

**Where it came from** (`core/card` `QuickNoteSource`, `app/quicknote` `QuickNoteIntent`, `QuickNoteApp`;
keething-spec app.quick-note-source; the Mac's `QuickNoteSource`). The app is the sending or calling app named by
`Activity.getReferrer()` (a share's sender, not the system's chooser; the selection menu's caller, Firefox's too, which
does not start it for a result; a sender may name another app with `EXTRA_REFERRER`, accepted: it is only a tag), its
label as the launcher shows it (`Chrome`, `Firefox`, `Gmail`). None for Keething itself, a referrer that is no app, an
app that is not installed or not visible, or one without a label of its own (its package name is not a name). The tag
is that name, a `,` or `;` in it a space, spelt as a tag of that name already in the database is (compared as tags
are: case and accents aside), added once. The page comes only from a share, in the shapes the browsers give
(`plans/done/android-notes.md` AQ0): a lone http(s) address shared is a page, its address the Website, and the note's text
the subject (the page's title, which Chrome gives and Firefox gave by the AQ3 round, not yet in AQ0's) or, with none,
the address too, since a note needs its text; Firefox's "Share via" and Chrome's "Including link: …" are no title; a
text whose last line, after a line break and blanks, is an http(s) address with a text directive (`#:~:text=`: Chrome's
shared selection, and the other Chromium browsers') gives that link to the Website and the rest as the text, the
quotes the browser put around the selection taken off (only when the rest is wrapped in them). Anything else gives no
page, an address in the middle of a text or a selected one (the selection menu) too: nothing is guessed. An address
is `http` or `https` with a host (a letter or digit in it), no white space, control character or invisible format
character in it (a direction override, a zero-width space; an internationalised name's joiners are allowed), and kept
whole: a `user:password@`, a token, and the text directive, which holds the
selected words and a few around them (the Website is not protected; the owner's choice, P5). Android's browsers do not
tell a private window (Chrome's incognito shares as a normal tab does), so none is left out for that. Both go into the
quick note's editor as an ordinary tag and Website, the Website row shown (it stays while its address is taken out),
a Website typed already kept; either can be taken out before Save, and a removed one is not saved. They travel with
the text: held with it while the lock screen waits, dropped with it when the quick note is given up, in the editor's
`NewNote` after; neither is printed (`QuickNoteSource` says nothing of them when printed).

**For the phone checks**: `task notes:database -- <new.kdbx>` writes a fictitious database through the phone's own
core (`core/vault` test `probe/MakeNotesDatabase.kt`, password `pw`): a protected note, an unprotected one, an entry
with Notes only and an ordinary entry with a password and protected Notes.

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
  use it to keep the keyboard from suggesting words), a phone keypad only with a phone word ("Telefonnummer",
  `phone`, "Tel."): apps take it for an amount too (a bank's transfer was offered "Unlock Keething", the owner
  2026-11-16). A form has usernames (or, when none, the phone number,
  or the unnamed text field right before the first password), passwords and codes. Nothing is offered on a
  sign-up (only new passwords: it gets "Generate a password" and the save offer, below), on Keething's own
  screens, or on a screen without such fields; asking for
  autofill by hand (long press › Autofill; in Chrome the text toolbar's ⋮ › Autofill) takes the focused unnamed field
  as the username, and a form without a current password (a sign-up, or a login whose password says `new-password` to
  keep browsers from filling it) gets the chosen entry's password in its new-password fields, "Generate a password"
  and the save offer kept. A password change gets its current password (and its username when the form shows one),
  never the new one; a new password keeps "Generate a password" and the save offer whatever else the form asks for,
  a reset's beside a code too (the code's entries fill the code). A phone's country or area code
  (`phoneCountryCode`, `tel-country-code`, `tel-area-code`; Chrome hands a page's `autocomplete` over as the field's
  hints) is ignored, so a username never goes there. Never a field's text, only what it is.
- **The target**: in a known browser (Chrome, Firefox and their forks, Brave, Edge, Samsung Internet,
  Vivaldi, DuckDuckGo, …; `Browsers`) the web domain of the page, with its scheme; fields of another domain
  (a frame of another site) are left out. A browser is known by its package and the certificate it is signed
  with: each listed package has the SHA-256 of its signing certificates beside it, and counts only when the system
  says it is signed with one of them (`PackageManager.hasSigningCertificate`, which takes a rotated key's older
  certificate too). An app installed under the package of a browser the phone does not have is signed otherwise,
  and is an app. Anywhere else the app's package name, which the system gives:
  an app's web domain is never trusted, since any app can show (or claim) a bank's page in a web view.
  Android's browsers name a page by its host only (`localhost` for `http://localhost:8000`, checked in
  Chrome and Firefox), so a port written in an entry matches any port then.
- **The entries** (`AutofillMatcher`): for a page, the Mac app's site matching (`SiteMatcher`: the same
  host first, then the same registrable domain by the public suffix list; the entry's URL, its
  `KP2A_URL` fields and its custom fields of type `url`; a protected field is never read), with one rule more: a plain `http://` page gets only
  addresses written with `http://`, so a password kept for an https site is never sent unencrypted. For an
  app: the entries that name it (`androidapp://<package>`, as Keepass2Android writes it, in any of those addresses), then the entries
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
- **Unlocked**: up to 6 matching entries, best first (title, username), counted among those with something for the
  form's fields, as the keyboard's chips (inline suggestions, Gboard) or the dropdown under the field. No value goes
  into the response: the system keeps a response for its screen and offers it again at each focus, a lock after it
  too (seen on the Pixel 6, 2026-11-20: Chrome's login page, its chips shown while unlocked, filled an entry with no
  unlock after the timeout had locked). A tap opens the autofill screen: invisibly while a database is open, which
  fills the entry's username and password at once; after a lock, the lock screen, then the entry. Then "Another entry", pinned at the keyboard's edge as a search icon: it opens
  the picker. With no entry offered, "Search Keething" is a chip with its title instead (a lone icon at the
  keyboard's edge passes for the keyboard's own search). A form that asks only for a code offers only
  entries with one.
- **One-time code**: an entry with a code, as every entry, is offered without its values; the tap opens the autofill
  screen invisibly, which makes the current code then and fills it (with the username and password when the form
  has them). Checked: the code filled in Chrome was the one keepassxc-cli showed. Firefox does not give a
  lone code field to autofill services at all (it registers login forms only); Chrome does.
- **The code after a login**: when autofill fills the username or password of an entry
  that has a one-time code and the form has no code field, the current code goes to the clipboard as any
  copy does (marked sensitive, cleared after 30 s, §4), so the next page's code field takes a paste: in
  Firefox, in a row of one-digit boxes, in a field autofill does not recognise. Its dataset goes
  through the invisible autofill screen as every entry's, so the code is made when it is tapped. The code may
  be near the end of its period; most sites also take the one before. Checked on the test phone (Chrome, the local
  login page): the username and password filled, the code pasted into another field was the one
  keepassxc-cli showed, and a paste 35 s later gave nothing.
- **Locked**: one item, "Unlock Keething" ("to fill <site or app>" in the dropdown). It opens the
  autofill screen: the lock screen (the fingerprint is offered at once), then the one matching entry is
  filled at once, or the picker shows when there are several or none. After a cancelled unlock the item
  still says "Unlock" for the rest of that screen's autofill session (the system keeps the first answer);
  while still locked it opens the lock screen again, and once a database is open it fills the one matching entry at
  once or shows the picker, as after an unlock.
- **The picker** (the autofill screen): "Choose an entry · to fill <site or app>", the search field at the
  bottom; nothing typed: "Suggested" (the matching entries), then recently used and all; typing searches
  every open database as the list does (URLs included), the recycle bins aside (`-` lists nothing, and the picker
  says nothing matches, as Send's list). A tap fills the entry and closes; ✕ closes without filling.
  Choosing an entry that does not match the target is the user's explicit choice; the title says where it
  goes.
- **Trust**: the autofill screen acts only on its own immutable intent (the fields, the target, the mode,
  the entry), never on the view structure the system adds to it: the app being filled starts that intent
  and could add a structure that names another app. The screen is not exported and has `FLAG_SECURE`. The app being
  filled could also keep an entry's intent and start it again later: each intent carries a random id the process keeps
  (`FillIntents`), and the screen answers without being seen once per id; an intent started again, or unknown (after
  a restart too), shows the picker, or the lock screen.
- **Never** offered: a protected custom value; an entry that matches neither the page's site nor the app.
  The log says kinds and counts only (`fill request: 2 fields, web page, 1 username, 1 password, 0 code`),
  never a value, a domain or an app.
- Not done, and why: a browser not listed, or whose certificate was not found (Mull, Ecosia: DEVELOPMENT.md),
  is an app; its page is not trusted; `android://<hash>@<package>` URLs
  (Chrome's way to name an app; not known to be in the owner's database); honouring an app's
  `importantForAutofill="no"` (apps set it on login fields too).
- **Save**: a form with a password gets `SaveInfo` (the password, or a sign-up's, a password change's or a reset's
  new one, required; the username optional, and beside a new password the code; also when all its fields go, for an
  app that changes screens), locked or not, so the system offers "Save username and password to Keething" after the
  form is sent with one of those fields not as a dataset filled it (a login filled from an entry and sent unchanged is
  not offered: the system compares; a generated password is a dataset's too, so its offer comes from the username or
  the code typed beside it, and a password change whose current password was filled gets none). On Save, the service reads what was typed (`ScreenFields` with the texts, the one place it does;
  the username from an earlier screen of the same form when this one has none) and keeps it in the app's
  memory as a wipeable secret for up to 10 minutes (`PendingSaves`: wiped then, whether the save screen took it or
  not); the save screen's intent carries only a
  random token, so the password never goes through the system in an intent. The save screen
  (`SaveLoginActivity`, not exported, `FLAG_SECURE`) unlocks first if need be, then decides
  (`SavePlanner`, `core/autofill`): the matching entry with the username typed (any case) — or, without one,
  the one whose current password was typed in a password change, or the only matching entry — gets the new
  password: the editor of that entry opens with it, under "Update the password of “…”?" and "Save as a new
  entry instead"; it has the password already: "Already saved in Keething" and nothing written (unlocked the system
  does not ask for a login filled and sent unchanged; one typed by hand with the password the entry has gets the same
  answer); otherwise the new-entry editor opens prefilled: title the page's host without `www.` or the app's name, the
  username, the password, the address (the page's, `https://www.shop.example`, `http://localhost:8000` for a plain
  http page; or `androidapp://<package>`), the root group to start with. With every open database read-only, the
  screen says "No open database takes new entries: open one that is not read-only." and keeps the login; the editor
  shows once one that takes new entries is open. Saving is the editor's (§3.3, "Saving and
  checking…", then Done and a toast back in the app or browser).
- **Generate**: a new-password field (a sign-up's, a password change's, a reset's beside a code, by hand too) gets
  "Generate a password" (a wand), also locked: it opens the password generator (§6.2) over the app or page; "Use this password"
  fills every new-password field (the repetition too), and the save offer then carries it. Nothing of an
  entry is offered on a sign-up.
- Not done, and why: choosing an entry by hand for an app does not yet offer to add its `androidapp://`
  address (the idea `autofill.remember-app`); a password change's "which entry" by the current password is
  JVM-tested only.
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
- Checked on the Pixel 6, 2026-11-20 (debug build, `autofill.kdbx`, Chrome, the local pages with `reset`,
  `login-new-password` and `phone`): a chip tapped after the timeout locked asked for the unlock, then filled; open,
  filled with nothing seen; the reset page offered the code's entry on the code, "Generate a password" on the new
  password and, the code typed, the save offer; the `new-password` login and a sign-up filled by hand; the country
  code left empty; the save offer with only a KDBX 3.1 database open (view only) said no database takes new entries,
  and showed the editor once `autofill.kdbx` was opened, its entry saved there.

## 8. Build and install

- Built on the Mac from the command line (JDK 21, Android command-line tools, the Gradle wrapper); Android
  Studio is optional.
- Release builds are shrunk with R8 (about 3 MB, against 66 MB for a debug build with the whole icon set).
- Release APK signed with the owner's own keystore (kept outside the repository, password not in files;
  `tools/build-apk.sh`, DEVELOPMENT.md); installed with `adb install -r` (USB or wireless debugging) or by copying the
  APK to the phone. Until 2026-10-21 the test builds were signed with the Mac's debug key instead (a known
  password: whoever has that file can sign an update the phone accepts); the owner's phone gets the own key.
- The build is reproducible (keething-spec app.fdroid-build): a commit builds the same unsigned APK in any folder, and
  the signed APK is that APK and its signing block alone (signed with v2/v3 only, no v1 since 2026-11-16, the
  alignment kept), so F-Droid can ship our signature; `task apk:reproduce` checks a release against a fresh clone. No
  dependency block in the APK; F-Droid's recipe is `tools/fdroid/dev.keething.android.yml`.
- Updates keep the same signing key (otherwise Android refuses the update and the app must be uninstalled,
  which deletes the biometric key and settings).
- An update keeps what the app stores (keething-spec app.upgrade-safety): the `settings` carry `storeVersion`
  (`SettingsVersion`: 1; none was kept before it, 0.0.1), turned into the current one at the start before anything
  reads them, a step per change of how something is kept; the list (`version`), the saves' notes (`version`) and the
  fingerprint's files (`KPBR`/`KPBS` 1) carry their own. `app/src/test/resources/stored/<release>` keeps what each
  release stored, and `StoredStateTest` reads it all back; each release adds its own, and before each one its release
  build is installed over the previous release on the test phone.
- minSdk 34 (only the owner's phone and an emulator), targetSdk = the newest installed (Android 17).
- Google's developer verification for side-loaded apps (rolling out 2026–2027): check its state before the
  first install; installing over `adb` is expected to stay possible.

### 8.1 Google Play (keething-spec `app.public-release`, was ROADMAP step 8 and RELEASE.md)

Decided (2026-10-21): the app is to be published on Google Play. It needs the SAF storage (§3.1, done: Play does not
allow all-files access for a password manager), a privacy policy, a store listing, the licences shown in the
app, and a donation option. `QUERY_ALL_PACKAGES` (autofill's verified websites, §7) is a restricted permission
on Play: check that a password manager's autofill is an accepted use, else matching apps by their websites
goes. External donation links are a grey area under Play's payment rules; the safe ways are
a "tip" through Play Billing or a separate paid "supporter" build (as KeePassDX does) — check the rules at
that time. A donation link, if any, comes only with the Play release.

## 9. Fixtures

`fixtures/` holds fictitious databases copied from the Mac project (password `pw`): KDBX 4 with AES + AES-KDF,
AES + Argon2d, ChaCha20 + Argon2id, a KDBX 3.1 one (`kdbx3.kdbx`, read and viewed only; with its version set to 2.0
it is the refused one), `kdbx4-export.xml` (keepassxc-cli's XML export of
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
  ("1 attachment stays on this phone"), history and the icon never do. Each part's row shows the start of its value,
  never a protected one: a password, a protected field and protected Notes (a note's text by default, §6.4) are dots.
  A note arrives as it was (keething-spec sharing.content AC9, sharing.receiving AC9): Notes sent protected are
  written protected, others as the receiving database protects new Notes; the kind (`Keething.EntryType`, §6.4) goes
  whatever parts are switched off, as the title does, and is written as it was stored, a value this version does not
  know too. Notes' protection goes only with the Notes. The sender names themselves ("From", remembered).
- **What of a wallet never goes** (`WalletSecrets`, `Sending.neverSent`). A recovery phrase (`crypto.seed`), its
  passphrase (`crypto.passphrase`) and a private key typed `crypto.xpub` (`xprv…`) are no part to switch on: What
  to send shows them as locked rows, "Recovery phrase · stays on this phone: whoever has these words has the
  wallet, on every network. To give someone coins, send them to their address." ("Passphrase …: with the recovery
  phrase it opens the wallet"; "Private key …: whoever has it can spend the wallet's coins"). Coins are given by
  sending them to an address; "send me your recovery phrase" is the commonest crypto theft, and a warning clicked
  through once loses the wallet. Untyped, failing closed, by the value, protected ones too: what the offer to type
  calls a phrase (a value whose BIP-39 or Electrum check passes, or a phrase's name with words of a list or a
  protected value; then also the field it would type as the passphrase) in a custom field, the password or the
  notes as a whole; an extended private key by its prefix or what it holds, checksum or not; a WIF private key (Base58Check, version 0x80 or
  0xEF). Not caught: a phrase inside longer notes; an Ethereum private key (64 hex digits look like any other
  secret). An extended public key (`crypto.xpub`) is off to start with: "With it, <name> sees every address and
  balance of the wallet, past and future, and one leaked key of any of its addresses opens them all" (BIP-32: an
  xpub and any one child private key give every key of the account). Addresses and the derivation path go as any
  field. No format change: an older phone could still send a phrase; received, a field typed `crypto.seed` or
  `crypto.passphrase`, or a private or protected `crypto.xpub`, has no copy button, as on the card. Sealed with HPKE (RFC 9180, base mode: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM),
  `info` "Keething answer v1" followed by the request's whole SHA-256: it opens only for the very request it
  answers, its name, reason and validity included. Making it removes the request on the sender's phone and keeps
  its id until it expires: one answer per request, the same request read again is "Answered already". The answer
  stays on its screen until Done.
- **Receiving.** The asking phone opens it with the request's private key: "From <name>", the entries with their
  fields (protected ones masked with an eye, protected Notes too, a note's text named Note; each copied as the card
  copies), and "Only this phone could open it,
  but anyone who saw your request could have made it, and the name is theirs to choose". Under a wallet's address
  (typed `crypto.address`, or read as one): "Check it with <name> another way before paying to it"; an envelope's
  come from whoever gave its pictures, without the note. Save makes each entry a
  new one in the group chosen on the group picker (the editor's, above; the top level to start with; new groups typed
  are made with the first entry, the rest go into them), while two or more databases are open in the database
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
  algorithm), then zeros up to a multiple of 128 bytes (its length says little about a password's). Version 2
  (since 2026-11-01, sharing.content AC9) has after each entry's notes a byte 1 when they are protected, and the kind
  (a byte 1 and its stored value, or 0); it is written only when an entry sends protected Notes or has a kind, else
  version 1, so a phone that reads only version 1 (before this change) still gets everything else, and refuses a
  version 2 as one it does not know. Both are read; any other version is refused. Read
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
  unlocked, wiped on lock. A file the storage cannot give is left as it is and nothing replaces it. One that can
  never be read (the key gone, so `SharingKey` made a new one; its content damaged; a version this app does not
  know) is given up at once: the book starts empty, the file goes, and the moment alone is kept for a week in
  `sharing/requests.lost` (no name, no secret; a request lives a week at most). Sharing says it once, a banner
  "Saved requests could not be read" until OK; an answer to no request here within that week says "Its request
  was lost … Ask for it again" instead of "Not for a request on this phone" (decided by the owner 2026-11-15:
  everything in the book expires within days, so starting again costs one more request; who was asked can only
  be named once contacts are kept, keething-spec sharing.contacts). Not keyed to the
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
  sheet leaves out Keething's own "Read with Keething" and Keething note (§6.4), as every share sheet of the app does
  (`ShareSheet`, §6.2).
- **Reading**: the back camera (CameraX, frames at about Full HD, ZXing); pictures from the system's photo
  picker, several at once (all of an answer's parts); pasted text; and "Read with Keething" in other apps' share
  sheet (beside Keething note, which takes a text as a quick note, §6.4): a picture, several, or a text from a
  messenger or mail, read at once by a see-through activity of its
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
