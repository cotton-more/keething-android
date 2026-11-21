What release 0.0.1 (keething-android 44f59a5, before 1.0) keeps in the app's private storage, as a sample for
`StoredStateTest` (keething-spec app.upgrade-safety AC4): the `settings` SharedPreferences, a stopped save's note, and
a backup of the first database in `files/backups/<its id>/` (a copy of `fixtures/kdbx4-aes-aeskdf.kdbx` under 0.0.1's
name for it; 0.0.1 wrote no `.sha256` beside its backups).
Written by hand from that commit's writers (`DatabaseList`, `AppSettings`, `BiometricSetup`, `RecentEntries`,
`GeneratorOptions`, `SaveNotes`), with the state set up on the Pixel 6 before the update checked there on 2026-11-06:
two databases in one granted folder, one with a key file; the fingerprint on for one, asked and declined for the other.
A release from 1.0 on adds its own folder, taken from a debug build of that release.
What the sample cannot hold: the Keystore's keys (the fingerprint's stored passwords, sharing's key) live in the
phone's Keystore, not in the app's files, and cannot be copied into a test.
