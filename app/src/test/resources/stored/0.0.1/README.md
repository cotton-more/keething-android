What release 0.0.1 (keething-android 44f59a5, before 1.0) keeps in the app's private storage, as a sample for
`StoredStateTest` (keething-spec app.upgrade-safety AC4): the `settings` SharedPreferences and a stopped save's note.
Written by hand from that commit's writers (`DatabaseList`, `AppSettings`, `BiometricSetup`, `RecentEntries`,
`GeneratorOptions`, `SaveNotes`), with the state set up on the Pixel 6 before the update checked there on 2026-11-06:
two databases in one granted folder, one with a key file; the fingerprint on for one, asked and declined for the other.
A release from 1.0 on adds its own folder, taken from a debug build of that release.
