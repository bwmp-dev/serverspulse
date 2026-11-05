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
| Minestom (embedded library, Java 25) | 26.2 / Minestom 2026.10.07-26.2 | In-process instance/snapshot and lifecycle test | `serverspulse-minestom-*.jar` |
| Velocity 3 (Java 17+) | Proxy | Compiles against API 3.4.0; live load not verified here | `serverspulse-velocity-*.jar` |
| Velocity 4.3.1 (Java 25+) | Proxy | Compiles against API 4.3.1; live load not verified here | `serverspulse-velocity-v4-*.jar` |
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

## Embedded Minestom library

Minestom has no plugin loader. Publish the bundled library locally with
`./gradlew :platform-minestom:publishToMavenLocal`, then use Maven coordinates
`com.serverspulse:serverspulse-minestom:1.0.0` (or the current build version).
Gradle consumers must enable `mavenLocal()`. Maven uses the local repository
by default:

```xml
<dependency>
  <groupId>com.serverspulse</groupId>
  <artifactId>serverspulse-minestom</artifactId>
  <version>1.0.0</version>
</dependency>
```

After `MinecraftServer.init()`, start the agent from your Java application:

```java
import com.serverspulse.agent.minestom.ServersPulseMinestom;
import java.io.File;

var monitoring = ServersPulseMinestom.start(
    new ServersPulseMinestom.Options(new File("serverspulse"), 100));
// During application shutdown, before MinecraftServer.stopCleanly():
monitoring.stop();
```

Use Java 25 and Minestom `2026.10.07-26.2`. The published jar bundles the agent
and relocates Gson, OkHttp, Okio, SnakeYAML and Kotlin; it does not bundle
Minestom. Include it in your application's shaded jar. `maxPlayers` is supplied
by the application (default 0 means unspecified), since Minestom has no global
slot limit. Instances are identified by UUID and expose dimension keys, entity,
chunk and player counts. Recreated instances have new UUIDs. MSPT comes from
Minestom's tick monitor; TPS is null because its configurable tick rate makes
the shared tracker's derived, capped-at-20 TPS misleading. The dashboard and
backend already support null TPS. Player ping and instance membership are
reported; AFK, client brand and protocol tracking are not claimed.

Join/leave listeners and collection tasks are removed by `stop()`/`close()`.
For an optional application-owned console command, pass arguments following
`serverspulse` to `monitoring.executeConsoleCommand(args)`; do not route
untrusted player input to this privileged console hook. Spark console execution
remains gated by the existing explicit YAML opt-in.

## Environment and JVM configuration overrides

All platforms accept these overrides at startup and reload. Precedence is JVM
system property, then environment variable, then `config.yml`, then defaults.
An explicitly empty value wins precedence; invalid interval/boolean overrides
fail loading without logging their values. Overrides are never saved to YAML.

| Environment variable | JVM property | Purpose |
| --- | --- | --- |
| `SERVERSPULSE_API_KEY` | `serverspulse.api-key` | Server API key |
| `SERVERSPULSE_BACKEND_URL` | `serverspulse.backend-url` | Trusted backend URL |
| `SERVERSPULSE_DATA_FOLDER` | `serverspulse.data-folder` | Configuration and geolocation cache directory |
| `SERVERSPULSE_INTERVAL_SECONDS` | `serverspulse.interval-seconds` | Snapshot interval, integer at least 5 |
| `SERVERSPULSE_COLLECT_PLAYER_METRICS` | `serverspulse.collect-player-metrics` | Literal `true` or `false`; session/ping collection |

For example, set `SERVERSPULSE_API_KEY` through the launcher's secret environment
and use `-Dserverspulse.interval-seconds=10` before `-jar`. Give each process
its own API key and data folder. Registration writes to the selected folder,
but an overridden API key still wins on reload. Display names remain owned by
the dashboard. `SERVERSPULSE_COLLECT_PLAYER_METRICS=false` does **not** disable join/leave
events and is not a performance-only privacy mode.

## Custom telemetry

The stable Java facade `com.serverspulse.agent.api.ServersPulse` is included,
unrelocated, in every shaded agent jar, including the published Minestom library.
Call it after starting the embedded agent. All four methods return `boolean`:
`false` means disabled, invalid input, or a local limit was reached. Calls before
startup and after shutdown are harmless no-ops.

```java
import com.serverspulse.agent.api.ServersPulse;
import java.util.Map;

// After your ServersPulseMinestom instance has started:
ServersPulse.gauge("match.queue_size", 12.0);
ServersPulse.counter("match.completed", 1.0);
ServersPulse.event("match.finished", Map.of("mode", "duel", "duration_seconds", 94));
ServersPulse.annotate("Deployed game rules revision 12");
```

Signatures: `gauge(String, double)`, `counter(String, double)`,
`event(String, Map<String, ?>)`, `annotate(String)`, all static and returning boolean.
Names must match `[a-zA-Z][a-zA-Z0-9_.-]{0,63}`. Counters accept nonnegative,
finite deltas; gauges accept any finite value. Gauges keep the last value per
collection interval; counters sum interval deltas. At most 64 distinct metric
name/kind pairs may be registered during a runtime, 100 events queued, and 10,000
accepted calls per minute. Events have at most 16 scalar fields; string values,
including annotations, are limited to 256 UTF-8 bytes. Nested fields are rejected.
The Free plan accepts 16 metric names and 20 events per snapshot; other plans
accept 64 and 100. Keep callers within their server's plan limits: a rejected
snapshot is not automatically trimmed.

Batches travel with snapshots and use their existing retry policy. Batch IDs
prevent custom counters/events from being duplicated by retries. Buffers are
in-memory; exhausted retries, crashes and shutdown can lose pending telemetry.
Do not use this API as a durable audit log. See the dashboard's
[custom telemetry documentation](https://serverspulse.com/docs/custom-telemetry/).

## Player privacy

Defaults are unchanged. Set `collect-player-data: false` to suppress join/leave,
player pings, sessions, switches, geolocation and extension transmission while
performance snapshots and aggregate online counts continue. This overrides
`collect-player-metrics`. Custom telemetry remains enabled: never put player
identifiers or other personal data in its free-form names or fields.

Set `hash-player-identifiers: true` to replace UUIDs and usernames with server-local
HMAC-SHA256 pseudonyms. A random 32-byte key is persisted as `player-identity.key`
in the resolved agent data folder. Keep it private and back it up for stable
identities. Removing it creates new identities; do not share it between servers.
The API key is not used as the salt, so rotating API keys preserves identities.
Hashes are not anonymous data: possession of this key permits testing known UUIDs.
No key is transmitted. Geolocation, join hostnames and plugin extensions are
suppressed in hashed mode because their free text may disclose identities.
Proxy/backend pseudonyms differ, so cross-server identity correlation is unavailable.
Existing backend history is retained when either mode changes.

| YAML | Environment | JVM property |
| --- | --- | --- |
| `collect-player-data` (default `true`) | `SERVERSPULSE_COLLECT_PLAYER_DATA` | `-Dserverspulse.collect-player-data=false` |
| `hash-player-identifiers` (default `false`) | `SERVERSPULSE_HASH_PLAYER_IDENTIFIERS` | `-Dserverspulse.hash-player-identifiers=true` |

Precedence is JVM property > environment > YAML > defaults. Restart or reload the
agent after changes. Capability metadata announces `performance_only` and/or
`hashed_player_identifiers`. Server settings also expose **Collect player data**:
disabling it drops incoming player data independently of agent configuration and
retains historical data. Apply backend migrations before deploying these features.

For Velocity, install exactly one matching jar in `plugins/`: the regular jar on
Velocity 3 and the `velocity-v4` jar on 4.3.1. They share source and descriptor but
compile independently. The locally supplied 4.3.1 API declares Java 25, so this
variant requires Java 25 even on a proxy otherwise configured for an older JVM.
