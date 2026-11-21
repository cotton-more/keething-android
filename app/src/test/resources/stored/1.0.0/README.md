What release 1.0.0 (keething-android 45fbbbf) keeps in the app's private storage, as a sample for `StoredStateTest`
(keething-spec app.upgrade-safety AC4): the `settings` SharedPreferences, a stopped save's note and two databases'
backups in `files/backups/<its id>/`.
Taken on 2026-11-21 with `run-as` from a debug build of that commit on the emulator, set up from a new install: two
databases in one granted folder (`Documents/T11-sample`: copies of `fixtures/kdbx4-aes-aeskdf.kdbx` as T11 Personal and
`fixtures/keyfiles/keyfile-hex64.kdbx` with `hex64.key` as T11 Keyed, password `pw`); the fingerprint on for Personal
(its password stored by a typed unlock), declined for Keyed; three entries in Recent; the lock after 15 min, the
password reminder every 30 days, the dark theme, requests valid 7 days, a request asked as "Sample", quick notes into
Personal, the generator on a passphrase of 6 words joined by "."; a change saved in Personal (its backup and
`.sha256`), then a change in Keyed whose save was stopped before the new file went in (DEVELOPMENT.md "Stopping a save
in the middle on the phone", `rename .syncthing`, the process killed): its note and its backup, no digest written yet.
What the sample leaves out: `no_backup/` (the fingerprint's sealed passwords and keyring, sharing's requests and key),
which only the phone's Keystore opens; AC5's install over the previous release on the test phone covers it.
