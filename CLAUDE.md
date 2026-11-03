# Keething for Android — notes for Claude

- Start with SPEC.md (what the app does). What comes next is in `~/Projects/keething-spec` (see below); there is no
  .x/ROADMAP.md any more. A behaviour change updates SPEC.md in the same commit. Each step ends with an APK the
  owner installs on the phone. The agreed screen mock-ups are the canvas of SPEC §6.
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
- Build, test, install and phone commands go through `Taskfile.yml` (`task` lists them; `task test`,
  `task test:module -- kdbx`, `task install:debug`, `task apk:install`, `task screenshot`, `task spec:check` …)
  rather than typing the Gradle, adb or script command by hand. A command used often that is missing there is
  added to it.
- Wireless adb to the phone goes through `tools/adb-wireless.py` from the start (DEVELOPMENT.md: plain `adb pair` /
  `adb connect` fail on this Mac). Phone checks run on the test phone (Pixel 6), not the owner's main phone.
- Starting a plan stage or a new feature (usually a new session): do not start the work at once. First
  say in a few lines what the step will do, and which effort level it needs (a plan stage's "Effort" is the
  starting point; say so if it should differ and why). Then wait for the owner to set
  the level and say "ok". Mid-step, if a part turns out to need more (or less) depth, say so before doing
  it. The current level cannot be read reliably, so ask rather than assume.

## Specs: `~/Projects/keething-spec`

Features shared with the Mac app are tracked in `~/Projects/keething-spec`: each feature's acceptance criteria and
how far each platform is. Its README.md and FORMAT.md are the rules; read FORMAT.md's "Working rules" before the
first change there.

- What next: `./status --gaps` (android), the ideas (`./status --ideas`; the public release is `app.public-release`),
  the open plan stages (`plans/README.md`) and `checks/android.md` (what to look at on the phone).
  `./status <area or id>` shows one area or feature with every criterion.
- Work goes by feature: a session takes one feature (or what is left of one), area by area from the bottom layer up
  (`kdbx` first). A feature waiting for the owner's decision (`OPEN.md`) is skipped until it is taken.
- Before starting a feature, read `features/<area>/<name>.md` (or `features/<area>/<name>/README.md`; ideas are in
  `ideas/`): its acceptance criteria are the brief, its *Platform notes* say what is Android-only, the Mac code and
  tests are the reference.
- Finishing a feature or changing behaviour: in the same piece of work update the feature file and
  `coverage/android/<area>.md` (`test` / `code` / `partial` / `no`, with the file and `#symbol` that shows it), or put
  `// spec: <id>/AC<n>` in the test that checks it. Stamp the criteria whose Android evidence was checked against
  their wording (`task spec:stamp -- <id or id/ACn>…`), then run `./status --index` and `./status --check` there;
  the check must pass. It fails on a covered criterion reworded since its stamp ("changed since covered": check the
  evidence against the new wording, fix the line, stamp) and on a new line or `spec:` comment not stamped yet.
- A change that alters a criterion: edit the criterion, set the macOS line back to `partial` or `no` with a note
  saying what changed, and stamp it for Android only (`task spec:stamp -- <id>/AC<n>`).
- A bug that breaks a criterion goes on that criterion's coverage line (`partial` / `no` with a note), not into a
  separate list. A small thing no criterion covers goes to `checks/android.md`, or becomes a criterion or an idea.
  `OPEN.md` keeps only what needs the owner's decision.
- The spec is self-contained (its FORMAT.md, "Self-contained"): what another session needs (a handover, a plan, a
  research note, a decision) is written there, in the feature (shared criteria; this platform's *Platform notes*) or
  `plans/`, never into this project's `.x/` or any other file outside version control. The spec points only at what is
  committed here: SPEC.md by section, code and tests by their path from this repository's root. `./status --check`
  fails on anything else.
- A new idea from the owner (for either app): write it down there at once with
  `~/Projects/keething-spec/idea <area>.<name> "Title" [macos,android] "description"`.
- That repository is committed separately from this one (its own commit, same rules: commit when the owner asks).
