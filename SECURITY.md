# Security policy

Keething is a personal project, kept by one person in their spare time. There is no team, no support contract and
no promise of a fix; the app comes with no warranty (see LICENSE). Still, it keeps passwords, so a weakness in it
matters more than most bugs. If you find one, please report it privately first, so that it is not public before it
can be looked at.

## How to report

- **Do not open a public issue** for a vulnerability.
- Write to **pug-jackpot-bok@duck.com**. Once this repository is public, GitHub's private vulnerability reporting
  (the Security tab, "Report a vulnerability") works too.
- Say what you found, how to make it happen (steps, or a test database made for it, never one with real passwords),
  and the app's version and commit from About.

## What to expect

- Reports are read and answered on a best-effort basis, with no set time and no promise of a fix.
- If a fix is made, it is released before the details are published, and you are credited if you wish.
- If there is no answer within 90 days, you are free to publish what you found.
- If the project is no longer kept, the README says so.

## In scope

- The app: how it stores, shows, copies and forgets secrets; the lock; the fingerprint unlock and the password it
  keeps for it.
- Reading and writing KeePass (KDBX) databases and key files, including a crafted file that makes the app misbehave.
- Autofill: which logins are offered to which app or website, and the save offer.
- Sharing entries between phones (the requests, answers and envelopes, their QR codes and pictures).
- The release builds published on GitHub, IzzyOnDroid and F-Droid.

## Out of scope

- A phone that is rooted, unlocked by someone else, or running malware with the system's privileges.
- What other apps do with a value after you copy or autofill it.
- The cloud or sync app that carries your database file.
