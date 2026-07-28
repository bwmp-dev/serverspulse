plugins {
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

sourceSets {
    named("main") {
        java.srcDir("src/main/net")
    }
}

// See the note in the 1.21.1 module: the 1.20.1 merged jar supplies the
// Minecraft compile classpath for this band as well.
val forge120MergedJar = project(":platform-forge:v1.20.1")
    .layout
    .buildDirectory
    .file("moddev/artifacts/forge-${libs.versions.forge.mc1201.get()}-merged.jar")

val minecraftCompileStubJar by tasks.registering(Jar::class) {
    archiveBaseName.set("minecraft-1.20.1-api")
    archiveClassifier.set("for-1.21.11-compile")
    destinationDirectory.set(layout.buildDirectory.dir("minecraft"))

    dependsOn(":platform-forge:v1.20.1:createMinecraftArtifacts")

    from(forge120MergedJar.map { zipTree(it.asFile) }) {
        include("net/minecraft/**")
        include("com/mojang/**")
    }
}

val shade: Configuration = configurations.create("shade") {
    isTransitive = true
}

dependencies {
    implementation(project(":agent-core"))

    compileOnly(files(minecraftCompileStubJar.flatMap { it.archiveFile }))
    compileOnly(variantOf(libs.forge.universal.mc12111) { classifier("universal") })
    compileOnly(libs.forge.eventbus.mc12111)
    compileOnly(libs.forge.fmlloader.mc12111)
    compileOnly(libs.forge.fmlcore.mc12111)
    compileOnly(libs.forge.spi.mc12111)
    compileOnly(libs.forge.javafml.mc12111)
    compileOnly(libs.brigadier)
    compileOnly(libs.log4j.api)

    shade(project(":agent-core"))
    shade(libs.kotlin.stdlib)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(minecraftCompileStubJar)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(minecraftCompileStubJar)
}

tasks.shadowJar {
    configurations = listOf(shade)
    archiveBaseName.set("serverspulse-forge-1.21.11")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    mergeServiceFiles()

    // Compile-time shim only: must never be shipped, or Forge's module layer
    // will reject the mod for split-package net.minecraftforge.client.extensions.
    exclude("net/minecraftforge/client/extensions/**")

    relocate("com.google.gson", "com.serverspulse.libs.gson")
    relocate("okhttp3", "com.serverspulse.libs.okhttp3")
    relocate("okio", "com.serverspulse.libs.okio")
    relocate("org.yaml.snakeyaml", "com.serverspulse.libs.snakeyaml")

    exclude("com/google/errorprone/**")
    exclude("org/intellij/lang/annotations/**")
    exclude("org/jetbrains/annotations/**")
}

tasks.jar {
    exclude("net/minecraftforge/client/extensions/**")
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
