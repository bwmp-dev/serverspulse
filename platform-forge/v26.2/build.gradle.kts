plugins {
    alias(libs.plugins.neoforge.moddev)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

// Minecraft 26.x requires Java 25.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
}

// ModDevGradle in NeoForm mode supplies *vanilla* Minecraft 26.2 in official
// mappings with no loader patches applied. That matters here: this is a Forge
// mod, so pulling the Minecraft classes out of a NeoForge-patched jar (as the
// 1.21.x bands do with the 1.20.1 merged jar) would drag NeoForge extension
// interfaces onto the compile classpath. Forge's own classes come from the
// compileOnly artifacts below.
neoForge {
    neoFormVersion = libs.versions.neoform.mc262.get()
}

val shade: Configuration = configurations.create("shade") {
    isTransitive = true
}

dependencies {
    implementation(project(":agent-core"))

    compileOnly(variantOf(libs.forge.universal.mc262) { classifier("universal") })
    compileOnly(libs.forge.eventbus.mc262)
    compileOnly(libs.forge.fmlloader.mc262)
    compileOnly(libs.forge.fmlcore.mc262)
    compileOnly(libs.forge.spi.mc262)
    compileOnly(libs.forge.javafml.mc262)
    // Brigadier and log4j deliberately come from NeoForm's Minecraft
    // dependency set rather than the version catalog: Minecraft declares them
    // as strict constraints, so pinning our own versions here fails to resolve.

    shade(project(":agent-core"))
    shade(libs.kotlin.stdlib)
}

tasks.shadowJar {
    configurations = listOf(shade)
    archiveBaseName.set("serverspulse-forge-26.2")
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
    filesMatching("META-INF/mods.toml") {
        expand(props)
    }
}
