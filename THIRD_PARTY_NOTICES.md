# Third-party notices

ServersPulse Agent distributions bundle (shade) open-source libraries. Package
relocation prevents namespace conflicts but does not change their licenses.

| Component | Version used | License | Project |
| --- | --- | --- | --- |
| Kotlin standard library | 2.1.10 | Apache License 2.0 | https://github.com/JetBrains/kotlin |
| OkHttp | 4.12.0 | Apache License 2.0 | https://github.com/square/okhttp |
| Okio (transitive dependency of OkHttp) | 3.6.0 | Apache License 2.0 | https://github.com/square/okio |
| Gson | 2.10.1 | Apache License 2.0 | https://github.com/google/gson |
| SnakeYAML | 2.2 | Apache License 2.0 | https://bitbucket.org/snakeyaml/snakeyaml |
| JetBrains annotations (transitive Kotlin dependency) | As resolved by Gradle | Apache License 2.0 | https://github.com/JetBrains/java-annotations |

Some artifacts may also contain Kotlin runtime support modules selected
transitively by Gradle. Their notices and license texts remain applicable.
Build-time-only Minecraft platform APIs, mappings, Gradle plugins, test
dependencies, and the separately installed Spark profiler are not bundled
solely by virtue of being used to compile or test this project.

The Apache License 2.0 text is included in [LICENSE](LICENSE). Copyrights belong
to their respective owners. Consult the linked projects and the resolved Gradle
dependency report for complete version-specific notices.
