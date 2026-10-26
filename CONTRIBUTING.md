# Contributing

Contributions are welcome: bug reports, ideas and pull requests.

## Development setup
- JDK 17
- `./gradlew build` produces the jar in `build/libs/`
- `./gradlew runClient` / `./gradlew runServer` launch a dev environment

## Guidelines
- Keep the mod ID `common_bugfixes` stable.
- Logging must never block or crash the game: keep file work on the writer thread and wrap risky calls.
- New log lines use the existing `key=value` format.
- Add a config option for anything that can produce a lot of data.
- Open an issue before large changes.

## Pull requests
1. Fork the repo and create a branch.
2. Make your change and test it in-game on Minecraft 1.20.1 / Forge 47.
3. Describe what changed and why in the PR.
