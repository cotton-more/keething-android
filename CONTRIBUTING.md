# Contributing

Keething is a personal project, kept by one person in their spare time. Issues and pull requests are welcome, and are
looked at on a best-effort basis.

## Issues

- A bug: About (the ⓘ on the lock screen, or Settings › About) has Report a problem, which fills in the app's version,
  the Android version and the phone's model. Say what you did, what you expected and what happened.
- Never attach a database with real passwords. If a database is needed, make one for the purpose.
- A vulnerability is not an issue: see [SECURITY.md](SECURITY.md).
- An idea: say what you want to do and why; the app stays small on purpose (SPEC.md says what it is for).

## Pull requests

- For anything more than a small fix, open an issue first, so that no work is wasted on something that will not fit.
- [SPEC.md](SPEC.md) describes the behaviour: a change in behaviour updates it in the same pull request.
- Code, comments, commit messages and documentation are in English; match the code around your change.
- Tests: `./gradlew test` (or `task test`, see [DEVELOPMENT.md](DEVELOPMENT.md)) must pass. Tests use the fictitious
  databases in `fixtures/` (their password is `pw`).
- A new or changed screen is usable with TalkBack: every control named, no secret read aloud.
- By opening a pull request you agree that your contribution is licensed under GPL-3.0-or-later, as the project.
