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

### Why this layout

- Keeps maintenance cost manageable while covering major ecosystem anchors.
- Avoids separate codepaths for every minor release.
- Lets smoke tests validate runtime compatibility against real dedicated servers.

### Smoke Test Requirement

Every target in the table above should be validated by a smoke run that:

1. Boots a dedicated Forge server.
2. Loads the ServersPulse Forge jar for that target band.
3. Runs `serverspulse status` from the server console.
4. Verifies `[ServersPulse] Agent:` appears in logs.
