plugins {
    id("net.neoforged.moddev") version "2.0.140"
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("com.gradleup.shadow") version "9.3.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

neoForge {
    version = "21.1.219"

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
    archiveBaseName.set("serverspulse-neoforge-1.21.1")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    mergeServiceFiles()

    relocate("com.google.gson", "com.serverspulse.libs.gson")
    relocate("okhttp3", "com.serverspulse.libs.okhttp3")
    relocate("okio", "com.serverspulse.libs.okio")
    relocate("org.yaml.snakeyaml", "com.serverspulse.libs.snakeyaml")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(props)
    }
}
