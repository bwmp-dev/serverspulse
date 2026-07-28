plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.140"
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("com.gradleup.shadow") version "9.3.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

legacyForge {
    version = "1.20.1-47.3.0"

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

val shade: Configuration by configurations.creating {
    isTransitive = true
}

dependencies {
    implementation(project(":agent-core"))
    shade(project(":agent-core"))
    shade("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
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
