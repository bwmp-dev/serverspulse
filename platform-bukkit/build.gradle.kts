plugins {
    id("serverspulse.platform")
}

dependencies {
    implementation(project(":agent-core"))
    compileOnly(libs.spigot.api)
}

tasks.shadowJar {
    archiveBaseName.set("serverspulse-bukkit")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "description" to (project.property("description") as String)
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
