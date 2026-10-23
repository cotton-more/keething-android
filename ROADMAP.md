# Roadmap

In this order; each step ends with an APK the owner installs on the phone. What the app does is in SPEC.md.
The agreed screen mock-ups (SPEC §6) are the canvas https://claude.ai/artifact/VvguNamsGWxY4zQwsHUaEa;
the Compose screens are built from them.

Each step starts as CLAUDE.md says: a short summary and the effort level it needs, then the owner's "ok".
The "Effort" line of a step is the suggested level (medium / high / xhigh / max).

## Later

Effort: per item, said when it starts.

Setting up one-time codes (secret, link, QR from the camera), merging a Syncthing conflict copy, restoring a
version from the history, adding / removing / renaming attachments, reordering custom fields, autofill:
choosing an entry by hand for an app offers to add its `androidapp://` address, a native Argon2 (e.g.
argon2kt) if the unlock feels slow (2.3 s on the Pixel 6, almost all of it the key derivation; SPEC §4).

**Contacts for sharing** (SPEC §10; agreed to come after the first version): a person's key kept after one
check of the pictures, so that sending to that person needs no request each time, and the answers
authenticated (both sides' keys, HPKE's auth mode or a signature), which closes the gap that anyone with a
request can answer it. The keys belong in the database (the Mac, a lost phone); a contact may remember the
app to send through.

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
