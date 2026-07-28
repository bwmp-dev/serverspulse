# ServersPulse Agent

The open-source, server-side Minecraft agent for
[ServersPulse](https://serverspulse.com). It sends health, performance, world,
and player activity data to the ServersPulse monitoring service.

## Installation and registration

1. Download the JAR for your platform from
   [Modrinth](https://modrinth.com/plugin/serverspulse),
   [Hangar](https://hangar.papermc.io/bwmp/ServersPulse), or
   [GitHub Releases](https://github.com/bwmp-dev/serverspulse/releases).
2. Stop the server and place the JAR in:
   - `plugins/` for Bukkit, Spigot, Paper, Purpur, or Folia.
   - `mods/` for Fabric, Forge, or NeoForge.
3. Start the server. The agent creates `config.yml` in its plugin/mod data
   directory but remains disabled until it has an API key.
4. In the [ServersPulse dashboard](https://serverspulse.com), add a server and
   generate a one-time registration code.
5. From the server console or as an operator, run:

   ```text
   /serverspulse register <code>
   ```

   The code is exchanged for an API key, the key is written to `config.yml`,
   and monitoring starts immediately. Treat both the code and API key as
   secrets. Alternatively, set `api-key` manually and restart the server or run
   `/serverspulse reload`.

No client mod is required. Fabric installations require Fabric Loader and may
require Fabric API, depending on the server version.

## Minecraft and platform compatibility

The ranges below reflect the artifact metadata and current real-server test
matrix. Versions between tested endpoints are intended to work where a range is
shown, but the explicitly tested versions provide the strongest compatibility
signal.

Minecraft changed its version scheme after 1.21.11: the releases that follow are
numbered `26.1`, `26.2`, and so on. Both schemes appear in the table below.

| Platform | Intended Minecraft versions | Explicit test coverage | Artifact |
| --- | --- | --- | --- |
| Bukkit / Spigot / Paper / Purpur / Folia | 1.8.8–26.2 | Paper 1.8.8, 1.21.11, and 26.2 | `serverspulse-bukkit-*.jar` |
| Fabric | 1.14.4–26.2 | Fabric 1.14.4, 1.21.11, and 26.2 | `serverspulse-fabric-*.jar` |
| Forge | 1.16.5 | Forge 1.16.5 | `serverspulse-forge-1.16.5-*.jar` |
| Forge | 1.18.2–1.20.6 | Forge 1.18.2, 1.19.2, and 1.20.1 | `serverspulse-forge-1.20.1-*.jar` |
| Forge | 1.21.1–1.21.10 | Forge 1.21.1 | `serverspulse-forge-1.21.1-*.jar` |
| Forge | 1.21.11 | Forge 1.21.11 | `serverspulse-forge-1.21.11-*.jar` |
| Forge | 26.2 | Forge 26.2 | `serverspulse-forge-26.2-*.jar` |
| NeoForge | 1.20.6–1.20.x | NeoForge 1.20.6 | `serverspulse-neoforge-1.20.6-*.jar` |
| NeoForge | 1.21.1–1.21.x | NeoForge 1.21.1 | `serverspulse-neoforge-1.21.1-*.jar` |
| NeoForge | 26.1–26.1.x | NeoForge 26.1.2 | `serverspulse-neoforge-26.1.2-*.jar` |

The Bukkit and Fabric artifacts are single version-agnostic jars: they are
compiled against their oldest supported release and resolve everything newer
through reflection, so one download covers the whole range. Forge and NeoForge
ship one jar per band because their APIs change incompatibly between versions.

Java requirements follow Minecraft and platform requirements: Java 8 for older
Bukkit and Forge 1.16.5 servers, Java 17 for the middle releases, Java 21 for
1.20.6/1.21 releases, and Java 25 for Minecraft 26.x. Use the Java version
required by your server. Forge 1.12.2 source exists for development but is not
part of the supported release build.

## Commands and permissions

`/sp` and `/pulse` are aliases of `/serverspulse`.

| Command | Purpose |
| --- | --- |
| `/serverspulse register <code>` | Exchange a one-time dashboard code for an API key. Replaces an existing key after warning. |
| `/serverspulse status` | Show whether the agent is running, platform and Minecraft version, interval, and backend URL. |
| `/serverspulse reload` | Reload `config.yml`, rebuild the connection, and restart collection. |

All commands require a server operator; the console is always allowed. On
Bukkit-family servers the permission node is `serverspulse.admin`, defaults to
operators, and may be assigned through a permissions plugin. Mod-loader
platforms enforce operator-level access and expose the same logical permission
where supported.

## Configuration reference

The generated `config.yml` uses these options:

```yaml
api-key: ""
backend-url: "https://api.serverspulse.com"
interval-seconds: 30
debug: false
allow-backend-spark-profiling: false
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `api-key` | empty | Secret used in the `X-API-Key` header. The agent does not collect until this is set. |
| `backend-url` | `https://api.serverspulse.com` | Destination service base URL. Change only when using a trusted compatible backend. |
| `interval-seconds` | `30` | Metrics snapshot interval. Minimum accepted value is 5 seconds. |
| `debug` | `false` | Enables additional capability, request, and collection logging. |
| `allow-backend-spark-profiling` | `false` | Opts in to backend-requested Spark profiling and result upload. See the trust boundary below. |

Changes take effect after `/serverspulse reload` or a server restart. The API
key is stored as plain text in the server data directory; restrict filesystem
and backup access accordingly.

## Telemetry and privacy

This is a monitoring agent, so telemetry is core functionality and is sent when
an API key is configured. It does not provide a general telemetry-off switch;
disable or remove the agent to stop monitoring. Requests use the configured
`backend-url` (HTTPS by default) and identify the server using its API key.

The agent sends the following:

| When | Data sent |
| --- | --- |
| Every configured interval | UTC timestamp; TPS; MSPT when available; JVM heap used and maximum; garbage-collection pause delta when available; online and maximum player counts; platform; Minecraft version; and, for every loaded world, its **world name**, dimension, entity count, loaded chunk count, and player count. |
| Player join and leave | Event type, the player's **Minecraft UUID**, current **player name**, and, on supported platforms for joins, the **virtual hostname/server address the player used to connect**. Hostname is null on leave and where unavailable. |
| Startup and reload | Agent version. |
| Update check | A request for stable release metadata; the response is locally compared with agent version, platform, and Minecraft version. |
| Registration | The one-time registration code. The returned API key is saved locally. |
| Spark profiling, only after explicit opt-in | Backend incident identifier and the public/private **Spark profiling result URL**, whose linked Spark report can contain detailed profiling results such as stack traces, plugin/mod activity, timing data, server configuration context, and other information collected by Spark. |

Ordinary metrics and player-event requests do not include chat content, player
IP addresses, world seed, world block contents, file contents, or console logs.
The agent does read `logs/latest.log` locally while an opted-in Spark session is
active to find the generated `spark.lucko.me` result URL; it uploads the URL,
not the log file. Data retention, account deletion, and service-side processing
are governed by the ServersPulse service terms and privacy policy.

### Backend command trust boundary

Successful metrics responses may contain operational commands. Currently the
only implemented command is `spark_profile`. It can run:

```text
spark profiler start --timeout <60-600 seconds>
spark profiler stop
```

The command is not registered and cannot execute under the default
`allow-backend-spark-profiling: false`. To opt in, install and trust Spark, set
the option to `true`, and reload. Opting in authorizes the configured backend to
start a Spark profile when an incident rule requests one and to receive its
result URL. Switching the option back to `false` and reloading stops an active
agent-managed session and removes that backend command handler.

Pointing `backend-url` at another service gives that service the same authenticated
telemetry access and, if Spark profiling is opted in, operational authority.
Only use a backend you trust.

## Build and test

The build spans four toolchains and needs all of them installed: JDK 8 (Forge
1.16.5), 17 (Bukkit, Fabric, Forge 1.20.1), 21 (Forge 1.21.x, NeoForge
1.20.6/1.21.1) and 25 (the Minecraft 26.x bands). Gradle itself runs on JDK 21.

```bash
./gradlew build --no-daemon
```

The first build also generates the official-mapped Forge 1.16.5 jars by running
the bundled ForgeGradle 5 setup build automatically; that step needs network
access, adds a few minutes once per machine, and is skipped from then on. See
[platform-forge/COMPATIBILITY.md](platform-forge/COMPATIBILITY.md) for why that
band needs separate tooling.

Consolidated release artifacts are written to `build/artifacts/`. The real-server
test lab requires Node.js 22 and, for its default release profile, Docker:

```bash
cd test-lab
npm ci
cd ..
node test-lab/scripts/run-test-matrix.js --profile quick
```

## Project links

- [Issues](https://github.com/bwmp-dev/serverspulse/issues)
- [Wiki](https://github.com/bwmp-dev/serverspulse/wiki)
- [Modrinth](https://modrinth.com/plugin/serverspulse)
- [Hangar](https://hangar.papermc.io/bwmp/ServersPulse)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## License

Licensed under the [Apache License 2.0](LICENSE). The ServersPulse name and logo
are trademarks of BWMP and are not licensed for use as branding for derivative
products. See [NOTICE](NOTICE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
