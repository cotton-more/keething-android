# Roadmap

In this order; each step ends with an APK the owner installs on the phone. What the app does is in SPEC.md.
The agreed screen mock-ups (SPEC §6) are the canvas https://claude.ai/artifact/VvguNamsGWxY4zQwsHUaEa;
the Compose screens are built from them.

Each step starts as CLAUDE.md says: a short summary and the effort level it needs, then the owner's "ok".
The "Effort" line of a step is the suggested level (medium / high / xhigh / max).

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

- First, the open question of SPEC §3.3 step 7: on the phone a file replaced by a rename in the Syncthing
  folder gets its old modification time back, and Syncthing does not send it. Find what resets it (the
  probe and the shell's `mv` show it; Syncthing-Fork 2.1.3 on the phone, 2.1.6 on the emulator, which did
  not), then choose: set the time after the rename, write in place, or both. Check with the storage probe
  on the phone that every write reaches the Mac.
- core: XML tree that keeps everything, writer, EntryEditing / EntryLifecycle / AttachmentPool, the save
  checks (port of `KDBXFileSaver`). JVM tests: written files read back identical; keepassxc-cli on the Mac
  opens them (the tests can call it when it is installed).
- app: editor, new entry, delete with Undo, recycle bin (`-`), restore, backups, replace by rename.
- Autofill: the save and update offers, the password generator for sign-ups.
- Check on a copy of the real database (as the Mac's `KDBXProbe --lifecycle`), then with Syncthing for real.

## Later

Effort: per item, said when it starts.

Setting up one-time codes (secret, link, QR from the camera), merging a Syncthing conflict copy, history,
adding / removing / renaming attachments, reordering custom fields, a native Argon2 (e.g. argon2kt) if the
unlock feels slow (2.3 s on the Pixel 6, almost all of it the key derivation; SPEC §4).

## Last, maybe: Google Play (SPEC §8.1)

Effort: xhigh (SAF storage replaces the file path; a security review before release).

The SAF `DatabaseStore` implementation, privacy policy, store listing, a donation option.
