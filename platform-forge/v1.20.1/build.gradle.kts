plugins {
    alias(libs.plugins.neoforge.moddev.legacy)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

legacyForge {
    version = libs.versions.forge.mc1201.get()

    runs {
        create("server") {
            server()
        }
    }

    mods {
        create("serverspulse") {
            sourceSet(sourceSets.main.get())
        }
    }
}

val shade: Configuration = configurations.create("shade") {
    isTransitive = true
}

dependencies {
    implementation(project(":agent-core"))
    shade(project(":agent-core"))
    shade(libs.kotlin.stdlib)
}

tasks.shadowJar {
    configurations = listOf(shade)
    archiveBaseName.set("serverspulse-forge-1.20.1")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    mergeServiceFiles()

    relocate("com.google.gson", "com.serverspulse.libs.gson")
    relocate("okhttp3", "com.serverspulse.libs.okhttp3")
    relocate("okio", "com.serverspulse.libs.okio")
    relocate("org.yaml.snakeyaml", "com.serverspulse.libs.snakeyaml")

    exclude("com/google/errorprone/**")
    exclude("org/intellij/lang/annotations/**")
    exclude("org/jetbrains/annotations/**")
}

obfuscation {
    reobfuscate(tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("shadowJar"), sourceSets.main.get())
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") {
        expand(props)
    }
}
