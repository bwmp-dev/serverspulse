plugins {
    id("serverspulse.base")
    id("com.gradleup.shadow")
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()

    relocate("com.google.gson", "com.serverspulse.libs.gson")
    relocate("okhttp3", "com.serverspulse.libs.okhttp3")
    relocate("okio", "com.serverspulse.libs.okio")
    // Bukkit/Spigot ship their own SnakeYAML on the server classpath (1.x on
    // legacy servers), so an unrelocated copy inside the plugin jar is a
    // classloader conflict waiting to happen. Every mod-loader module already
    // relocates this; the Bukkit artifact used to be the odd one out.
    relocate("org.yaml.snakeyaml", "com.serverspulse.libs.snakeyaml")

    // Annotation-only artifacts pulled in transitively by Gson. They are not
    // referenced at runtime and only add classes to every shipped jar.
    exclude("com/google/errorprone/**")
    exclude("org/intellij/lang/annotations/**")
    exclude("org/jetbrains/annotations/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
