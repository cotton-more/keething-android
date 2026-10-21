# KeePassBar for Android — notes for Claude

- Start with SPEC.md (what the app does) and ROADMAP.md (what comes next). Keep both up to date with the
  code: a behaviour change updates SPEC.md in the same commit; a finished ROADMAP step is removed from it.
- The Mac app in `~/Projects/keepass-bar` (Swift) is the reference for every KDBX, TOTP, search and card
  detail, and its tests are the reference for the expected results. Read the Swift code before porting a
  part; do not change that project from here.
- The owner writes in Russian; answer in English all the same. Answer in Russian only when the owner
  explicitly asks for an explanation in Russian. Code, comments, commit messages and docs are in English.
- Commits: no "Co-Authored-By" line. Commit when the owner asks.
- Security stance: best practice, no fanaticism; when something is skipped, say what and why.
- Never open, copy or test against the owner's real database without asking; tests use `fixtures/`
  (password `pw`).
- Report honestly what was verified on the phone and what was only built.
- Wireless adb to the phone goes through `tools/adb-wireless.py` from the start (DEVELOPMENT.md: plain `adb pair` /
  `adb connect` fail on this Mac). Phone checks run on the test phone (Pixel 6), not the owner's main phone.
- Starting a ROADMAP step or a new feature (usually a new session): do not start the work at once. First
  say in a few lines what the step will do, and which effort level it needs (the step's "Effort" in
  ROADMAP.md is the starting point; say so if it should differ and why). Then wait for the owner to set
  the level and say "ok". Mid-step, if a part turns out to need more (or less) depth, say so before doing
  it. The current level cannot be read reliably, so ask rather than assume.
