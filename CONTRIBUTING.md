# Contributing to ServersPulse Agent

Thanks for helping improve the agent. Bug reports, compatibility results,
documentation fixes, and focused code changes are welcome.

## Before opening an issue

- Search [existing issues](https://github.com/bwmp-dev/serverspulse/issues).
- Use the latest agent release and confirm the problem still occurs.
- Remove API keys, registration codes, player data, server addresses, and Spark
  result URLs from logs and configuration before posting them.
- For a security vulnerability, follow [SECURITY.md](SECURITY.md) instead of
  opening a public issue.

## Development setup

The complete build spans four toolchains and needs all of them installed:
JDK 8 (Forge 1.16.5), 17 (Bukkit, Fabric, Forge 1.20.1), 21 (Forge 1.21.x,
NeoForge 1.20.6/1.21.1) and 25 (the Minecraft 26.x bands). Gradle runs on 21.

```bash
./gradlew build --no-daemon
```

The first build additionally runs the bundled ForgeGradle 5 setup build to
generate the mapped Forge 1.16.5 jars. It needs network access, takes a few
minutes, and is skipped on every later build; see
[platform-forge/COMPATIBILITY.md](platform-forge/COMPATIBILITY.md).

Artifacts are collected in `build/artifacts/`. The real-server test lab also
requires Node.js 22 and, for its default release profile, Docker:

```bash
cd test-lab
npm ci
cd ..
node test-lab/scripts/run-test-matrix.js --profile quick
```

## Pull requests

1. Keep changes focused and explain their user-visible effect.
2. Add or update tests and documentation when behavior changes.
3. Run the Gradle build and the relevant test-lab targets.
4. Do not commit generated build output, credentials, production data, or
   proprietary ServersPulse service code.
5. Agree that your contribution is provided under the Apache License 2.0.

Maintainers may ask for compatibility evidence on the Minecraft and platform
versions affected by a change. Releases are produced by maintainers from signed
version tags after CI succeeds.
