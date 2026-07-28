plugins {
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

val forge120MergedJar = project(":platform-forge:v1.20.1")
    .layout
    .buildDirectory
    .file("moddev/artifacts/forge-1.20.1-47.3.0-merged.jar")

val minecraftCompileStubJar by tasks.registering(Jar::class) {
    archiveBaseName.set("minecraft-1.20.1-api")
    archiveClassifier.set("for-1.21.1-compile")
    destinationDirectory.set(layout.buildDirectory.dir("minecraft"))

    dependsOn(":platform-forge:v1.20.1:createMinecraftArtifacts")

    from(forge120MergedJar.map { zipTree(it.asFile) }) {
        include("net/minecraft/**")
        include("com/mojang/**")
    }
}

val shade: Configuration by configurations.creating {
    isTransitive = true
}

dependencies {
    implementation(project(":agent-core"))

    compileOnly(files(minecraftCompileStubJar.flatMap { it.archiveFile }))
    compileOnly("net.minecraftforge:forge:1.21.1-52.1.2:universal@jar")
    compileOnly("net.minecraftforge:eventbus:6.2.27")
    compileOnly("net.minecraftforge:fmlloader:1.21.1-52.1.2")
    compileOnly("net.minecraftforge:fmlcore:1.21.1-52.1.2")
    compileOnly("net.minecraftforge:forgespi:7.1.5")
    compileOnly("net.minecraftforge:javafmllanguage:1.21.1-52.1.2")
    compileOnly("com.mojang:brigadier:1.3.10")
    compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")

    shade(project(":agent-core"))
    shade("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(minecraftCompileStubJar)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(minecraftCompileStubJar)
}

tasks.shadowJar {
    configurations = listOf(shade)
    archiveBaseName.set("serverspulse-forge-1.21.1")
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
    filesMatching("META-INF/mods.toml") {
        expand(props)
    }
}
