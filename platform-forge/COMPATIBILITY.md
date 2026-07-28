## Forge Compatibility Layout

This repository uses a practical Forge support layout focused on high-usage versions and long-lived modpack targets.

### Target Bands

- `1.12.2` -> `platform-forge/v1.12.2`
- `1.16.5` -> `platform-forge/v1.16.5`
- `1.18.2` -> `platform-forge/v1.20.1` adapter band
- `1.19.2` -> `platform-forge/v1.20.1` adapter band
- `1.20.1` -> `platform-forge/v1.20.1`
- `1.21.1` -> `platform-forge/v1.21.1`
- `1.21.11` -> `platform-forge/v1.21.11`
- `26.2` -> `platform-forge/v26.2`

Minecraft replaced the `1.MINOR.PATCH` scheme with a calendar-style `YY.RELEASE`
scheme after 1.21.11, so `26.2` is the release *after* `1.21.11`, not before it.

### Why this layout

- Keeps maintenance cost manageable while covering major ecosystem anchors.
- Avoids separate codepaths for every minor release.
- Lets smoke tests validate runtime compatibility against real dedicated servers.

### How each band gets its Minecraft compile classpath

- `v1.16.5` compiles against official-mapped jars and reobfuscates its output
  back to SRG, because pre-1.20.2 Forge runs on SRG names. Those jars come from
  the bundled ForgeGradle 5 build in `v1.16.5-fg5-setup` (see below).
- `v1.20.1` uses ModDevGradle's `legacyForge` mode, which handles mapping and
  reobfuscation itself.
- `v1.21.1` and `v1.21.11` extract `net/minecraft/**` and `com/mojang/**` from
  the `v1.20.1` merged jar as a compile stub. Because that jar is Forge-patched,
  `v1.21.11` also carries a compile-only `IForgeBlockAndTintGetter` shim that is
  excluded from the shipped jar.
- `v26.2` uses ModDevGradle in NeoForm mode, which supplies *vanilla* Minecraft
  in official mappings with no loader patches applied. That avoids dragging
  another loader's extension interfaces onto a Forge mod's compile classpath,
  and is why this band needs no shim.

### The Forge 1.16.5 bootstrap build

Minecraft 1.16.5 predates Mojang's official mappings being usable by NeoForged's
tooling: NeoFormRuntime rejects it outright with *"NFRT currently does not
support MCP versions that did not make use of official Mojang mappings (pre
1.17)"*. ForgeGradle 5 is the only toolchain that maps this version, and it
requires Gradle 7, so it cannot be applied inside the main Gradle 9 build.

`platform-forge/v1.16.5-fg5-setup` is therefore a small standalone build with
its own pinned Gradle 7.6.4 wrapper. It resolves Forge 1.16.5 through
ForgeGradle 5, which writes the official-mapped jars and the SRG->official
mappings into the shared Gradle cache at
`~/.gradle/caches/forge_gradle/minecraft_user_repo/`.

The main build runs it automatically: `:platform-forge:v1.16.5`'s
`bootstrapMappedForgeJars` task invokes it before anything compiles, and skips
it once those files are present, so the cost is paid once per machine. It needs
network access and a Java 17 toolchain (ForgeGradle 5 cannot run on Java 21+).

To run it by hand, or to see a failure in isolation:

```bash
cd platform-forge/v1.16.5-fg5-setup && ./gradlew generateMappedForgeJars
```

The Forge version comes from `forge-mc1165` in `gradle/libs.versions.toml`; the
main build passes it through, and a standalone run reads the same entry.

ForgeGradle prints Mojang's mapping licence notice while it runs. Those mappings
may be used for development but not redistributed, which is why the mapped jars
are generated into each developer's Gradle cache instead of being committed or
published. Only the reobfuscated agent jar is shipped.

### Smoke Test Requirement

Every target in the table above should be validated by a smoke run that:

1. Boots a dedicated Forge server.
2. Loads the ServersPulse Forge jar for that target band.
3. Runs `serverspulse status` from the server console.
4. Verifies `[ServersPulse] Agent:` appears in logs.
