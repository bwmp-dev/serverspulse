plugins {
    alias(libs.plugins.neoforge.moddev)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

neoForge {
    version = libs.versions.neoforge.mc1211.get()

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
    archiveBaseName.set("serverspulse-neoforge-1.21.1")
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
