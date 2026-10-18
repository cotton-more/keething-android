# KeePassBar for Android — notes for Claude

- Start with SPEC.md (what the app does) and ROADMAP.md (what comes next). Keep both up to date with the
  code: a behaviour change updates SPEC.md in the same commit; a finished ROADMAP step is removed from it.
- The Mac app in `~/Projects/keepass-bar` (Swift) is the reference for every KDBX, TOTP, search and card
  detail, and its tests are the reference for the expected results. Read the Swift code before porting a
  part; do not change that project from here.
- The owner writes in Russian; answer in Russian. Code, comments, commit messages and docs are in English.
- Commits: no "Co-Authored-By" line. Commit when the owner asks.
- Security stance: best practice, no fanaticism; when something is skipped, say what and why.
- Never open, copy or test against the owner's real database without asking; tests use `fixtures/`
  (password `pw`).
- Report honestly what was verified on the phone and what was only built.
