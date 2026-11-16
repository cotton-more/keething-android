# Privacy

Keething collects nothing. It has no account, no analytics, no ads, no crash reporting and no network permission: it
cannot connect to the internet at all.

## What stays on the phone

- **Your databases** stay where you keep them. The app reads and writes only the files and folders you choose in
  Android's picker; it asks for no storage permission.
- **The app's own data**: the list of your databases, the settings, and the backups of each database (copies of the
  encrypted file, which you can turn off, delete and restore). They are kept in the app's private storage, excluded
  from Android's backups and from the transfer to a new phone.
- **The fingerprint unlock** keeps your master password encrypted with a key in the phone's secure hardware, usable
  only after your fingerprint. Turning the fingerprint off, or a new fingerprint on the phone, deletes it.
- **Unlocked secrets** are held in memory only while a database is open, and dropped when it locks.

## What leaves the app, and only when you do it

- **Copying** puts a value on the clipboard, marked sensitive so that Android does not show it, and clears it after
  30 seconds.
- **Autofill** gives the login you choose to the app or website you are filling. To match a login to an app, Keething
  looks at the apps installed on the phone; what it sees stays on the phone.
- **Showing and sharing a password**: Show as QR and large type show it on the screen only; Share… hands it, after
  a warning, to the app you pick in Android's share sheet, and that app then has it.
- **Sharing an entry** with another phone running Keething goes through a QR code or a picture, encrypted for that
  phone.
- **Report a problem** opens a new GitHub issue in your browser with the app's version, the Android version and the
  phone's model filled in, nothing from a database. You see it before you send it, and nothing is sent unless you do.

## Permissions

- **Fingerprint** (USE_BIOMETRIC): the fingerprint unlock.
- **Camera**: reading a one-time code's or a shared entry's QR code, asked for only when you start reading one.
- **See installed apps** (QUERY_ALL_PACKAGES): autofill's matching of a login to an app.
- **Foreground service**: keeps the app alive for the seconds it takes to write a new database.

Questions: open an issue, or write to the address in [SECURITY.md](SECURITY.md).
