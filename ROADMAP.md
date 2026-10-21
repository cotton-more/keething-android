# Roadmap

In this order; each step ends with an APK the owner installs on the phone. What the app does is in SPEC.md.
The agreed screen mock-ups (SPEC §6) are the canvas https://claude.ai/artifact/VvguNamsGWxY4zQwsHUaEa;
the Compose screens are built from them.

Each step starts as CLAUDE.md says: a short summary and the effort level it needs, then the owner's "ok".
The "Effort" line of a step is the suggested level (medium / high / xhigh / max).

## 7. Writing (blocks a Google Play release)

Effort: max (XML tree, writer, the save checks; a mistake can damage the database).

The app is read-only until this step: it cannot damage the database. Editing on the phone is rare for
the owner, so writing comes after autofill and before Google Play, without hurry.

Done already: the core (tree, writer, editing, the save checks), replacing the file by a rename with its
time set, the backups, the editor, new entry, delete with Undo, restore, the password generator, autofill's
save and update offers and the generator for sign-ups.

- Checked on a copy of the real database, on the Mac and on the test phone (SPEC §3.3: all saves passed; 3 s
  and about 380 MB of the 512 MB heap per save on the phone). Left: the owner's decision to use writing with
  the real database and Syncthing.

## Later

Effort: per item, said when it starts.

Setting up one-time codes (secret, link, QR from the camera), merging a Syncthing conflict copy, history,
adding / removing / renaming attachments, reordering custom fields, autofill: choosing an entry by hand for
an app offers to add its `androidapp://` address, field types in the editor (the Mac's
per-entry `KeePassBar.FieldTypes`; the card reads only the older KeePassBar entry's rules), a native Argon2 (e.g. argon2kt) if the
unlock feels slow (2.3 s on the Pixel 6, almost all of it the key derivation; SPEC §4).

An idea to think through first: **share an entry encrypted** — a link or a QR code that holds one entry
(like a database of one entry) encrypted, which KeePassBar on the other device recognises, decrypts and
shows. Share… and Show as QR stay as they are. Open: how the two sides agree on the key (a passphrase told
another way, a key in the link's fragment, a key exchange between the two apps), what format (a one-entry
KDBX the other side can also open in KeePassXC, or a small format of our own), and the size limit of a QR
code (attachments left out).

## 8. Google Play (SPEC §8.1)

Effort: xhigh (SAF storage replaces the file path; a security review before release).

Decided: the app goes to Google Play. What it takes (Play's rules to be checked again when the step starts):
- Storage without all-files access: Play allows `MANAGE_EXTERNAL_STORAGE` only for apps whose core purpose
  needs it (file managers, backup apps), not a password manager. The SAF `DatabaseStore` (the file chosen in the
  system picker, a persisted grant) must keep the promise of §3: never lose the file when Syncthing replaces
  it, notice new versions, write by replacing. To be tried on the test phone with Syncthing before anything else.
- `QUERY_ALL_PACKAGES` (autofill's verified websites of an app, §7): a restricted permission with a declaration
  form; if a password manager's autofill is not accepted, matching apps by their websites goes (entries with
  `androidapp://` still match).
- Release signing: an upload key of the owner's and Play App Signing; an Android App Bundle. The installs
  signed with the debug key cannot update to the Play version: uninstall, install from Play, set the
  fingerprint up again.
- The developer account (identity verification, the fee); a new personal account has to run a closed test
  with testers for some weeks before a production release.
- Store listing (text, icon, screenshots: from a debug build, `FLAG_SECURE` blanks them otherwise), content
  rating, the Data safety form (nothing collected, no network), a privacy policy page.
- The licences shown in the app (Google Sans Code OFL, qrcodegen MIT, the EFF word list CC BY needs its
  credit), not only in `assets/licenses`.
- A donation option (Play Billing tip or a supporter build; an outside link is a grey area).
- A security review of the whole app before the first release.
