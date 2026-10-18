# Contributing

Thanks for helping out. This is how to send a change.

## Branches

- `main` only gets tested releases.
- `develop` is where changes go. Open your pull request against `develop`.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Ask in an issue before adding a new dependency.
- Security problems go through [SECURITY.md](SECURITY.md), never a public issue.

## Building and testing

The build steps are in [README.md](README.md#building-from-source). The short version:

1. Install JDK 25.
2. Put the jars listed in [libs/README.md](libs/README.md) in `libs/`.
3. Run `./gradlew build`. It runs every test. The integration tests start their own MariaDB.

Every pull request runs the same build on GitHub. It has to pass before a change is merged.

## What a good pull request looks like

- One change per pull request, kept small.
- Tests for what you changed. A test should fail if your change is removed or broken. Check that
  by breaking the code on purpose and running the test.
- Anything that moves money or decides who gets paid needs those tests. `./gradlew billFaultChecks`
  and `./gradlew seamFaultChecks` must still catch every fault.
- New features ship switched off. An existing config must behave the same after your change.
- Match the style of the code around your change. Plain comments, no long ones.
- Player and owner text is short and plain. No dashes as punctuation in that text.

## License

ProsperityNPC is GPL-3.0-only. By sending a pull request you agree your change is shared under the
same license, and you confirm you have the right to share it.
