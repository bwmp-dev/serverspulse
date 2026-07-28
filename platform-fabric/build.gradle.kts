plugins {
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("com.gradleup.shadow") version "9.3.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

val shade: Configuration by configurations.creating {
    isTransitive = true
}

dependencies {
    minecraft("com.mojang:minecraft:1.14.4")
    mappings("net.fabricmc:yarn:1.14.4+build.18:v2")
    modImplementation("net.fabricmc:fabric-loader:0.10.5+build.213")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.28.5+1.14")

    implementation(project(":agent-core"))
    shade(project(":agent-core"))
    shade("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
}

tasks.shadowJar {
    configurations = listOf(shade)
    archiveClassifier.set("dev-shadow")
    mergeServiceFiles()

    relocate("com.google.gson", "com.serverspulse.libs.gson")
    relocate("okhttp3", "com.serverspulse.libs.okhttp3")
    relocate("okio", "com.serverspulse.libs.okio")
}

tasks.remapJar {
    inputFile.set(tasks.shadowJar.get().archiveFile)
    dependsOn(tasks.shadowJar)
    archiveBaseName.set("serverspulse-fabric")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

tasks.build {
    dependsOn(tasks.remapJar)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}
