# Databases made by other KeePass apps

Fictitious, password `pw`, made by the owner on 2026-11-05 for keething-spec `plans/android-release-1.0.md` T1
(`core/vault/src/test/kotlin/dev/keething/vault/opening/OtherAppsDatabasesTest.kt`). Each has groups Work › Projects
with an entry in each, an entry "Main" (tags `alpha`, `beta`, an expiry, a protected custom field `PIN` = `1234`,
the attachment `note.txt`, a custom icon, a one-time code with the secret `JBSWY3DPEHPK3PXP`, a history) and an
entry in the recycle bin.

| File | Made with | Format |
|---|---|---|
| `keepassxc-2.7.12.kdbx` | KeePassXC 2.7.12 on the Mac (the app, then keepassxc-cli for the groups, the entries, the history and the bin) | KDBX 4.1, AES, Argon2d |
| `strongbox-1.65.2.kdbx` | Strongbox 1.65.2 on the Mac; its own "Sample Group" and "Sample" entry kept | KDBX 4.0, AES, Argon2d; breaks KeePass's schema as Strongbox writes it |
| `keepassdx-4.5.8.kdbx` | KeePassDX 4.5.8 (libre) on the Pixel 6, with its settings as they come; its "Templates" group of seven entries kept | KDBX 3.1, AES, AES-KDF |

`keepassxc-2.7.12-passkey.kdbx` (keething-spec card.passkey; `core/vault/src/test/kotlin/dev/keething/vault/opening/PasskeyDatabaseTest.kt`):
`keepassxc-2.7.12.kdbx` with a fictitious passkey imported by the owner on 2026-11-21 in KeePassXC 2.7.12 on the Mac
(Import Passkey, a new entry): the entry "webauthn.io (Passkey)" in the group "Imported Passkeys", user
`alice.example`, URL `https://webauthn.io`, tag `Passkey`, and KeePassXC's seven `KPEX_PASSKEY_*` fields. The key is a
throwaway P-256 key made for it and registered nowhere.
