# Third-party notices

ServersPulse Agent distributions bundle (shade) open-source libraries. Package
relocation prevents namespace conflicts but does not change their licenses.

| Component | Version used | License | Project |
| --- | --- | --- | --- |
| Kotlin standard library | 2.4.10 | Apache License 2.0 | https://github.com/JetBrains/kotlin |
| OkHttp | 5.4.0 | Apache License 2.0 | https://github.com/square/okhttp |
| Okio (transitive dependency of OkHttp) | 3.17.0 | Apache License 2.0 | https://github.com/square/okio |
| Gson | 2.14.0 | Apache License 2.0 | https://github.com/google/gson |
| SnakeYAML | 2.6 | Apache License 2.0 | https://bitbucket.org/snakeyaml/snakeyaml |
| JetBrains annotations (transitive Kotlin dependency) | As resolved by Gradle | Apache License 2.0 | https://github.com/JetBrains/java-annotations |

Some artifacts may also contain Kotlin runtime support modules selected
transitively by Gradle. Their notices and license texts remain applicable.
Build-time-only Minecraft platform APIs, mappings, Gradle plugins, test
dependencies, and the separately installed Spark profiler are not bundled
solely by virtue of being used to compile or test this project.

The Apache License 2.0 text is included in [LICENSE](LICENSE). Copyrights belong
to their respective owners. Consult the linked projects and the resolved Gradle
dependency report for complete version-specific notices.

## Data downloaded at runtime

The optional geolocation feature (`geolocation-enabled` in the agent's
`config.yml`, off by default) downloads the **DB-IP IP to Country Lite**
database into the agent's data folder and refreshes it monthly. It is not
bundled in any artifact and nothing is downloaded while the feature is
disabled.

IP Geolocation by [DB-IP](https://db-ip.com)

The DB-IP IP to Country Lite database is made available by DB-IP under the
[Creative Commons Attribution 4.0 International License](https://creativecommons.org/licenses/by/4.0/).
Its licence requires attribution to DB-IP for the data, and requires any web
application that displays or uses results derived from the database to include a
link back to https://db-ip.com on the pages that do so. The attribution line
above satisfies the first requirement for the agent; any interface that surfaces
the country codes it derives must carry the same link.
