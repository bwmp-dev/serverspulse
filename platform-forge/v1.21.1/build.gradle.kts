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

// Forge does not publish a usable Minecraft API artifact for this band, so the
// 1.20.1 module's patched jar supplies the `net.minecraft` / `com.mojang`
// classes at compile time. The names this module touches are stable across the
// two versions; anything version-sensitive goes through reflection instead.
val forge120PatchedJar = project(":platform-forge:v1.20.1")
    .layout
    .buildDirectory
    .file("moddev/artifacts/forge-${libs.versions.forge.mc1201.get()}.jar")

val minecraftCompileStubClasses = layout.buildDirectory.dir("minecraft/stub-classes")

val extractMinecraftCompileStub by tasks.registering {
    dependsOn(":platform-forge:v1.20.1:createMinecraftArtifacts")
    outputs.dir(minecraftCompileStubClasses)

    doLast {
        sync {
            from(zipTree(forge120PatchedJar.get().asFile)) {
                include("net/minecraft/**")
                include("com/mojang/**")
            }
            into(minecraftCompileStubClasses)
        }
    }
}

project(":platform-forge:v1.20.1").tasks.named("createMinecraftArtifacts") {
    outputs.file(forge120PatchedJar)
    finalizedBy(extractMinecraftCompileStub)
}

val minecraftCompileStubJar by tasks.registering(Jar::class) {
    archiveBaseName.set("minecraft-1.20.1-api")
    archiveClassifier.set("for-1.21.1-compile")
    destinationDirectory.set(layout.buildDirectory.dir("minecraft"))

    dependsOn(extractMinecraftCompileStub)
    from(minecraftCompileStubClasses)
}

val shade: Configuration = configurations.create("shade") {
    isTransitive = true
}

dependencies {
    implementation(project(":agent-core"))

    compileOnly(files(minecraftCompileStubJar.flatMap { it.archiveFile }))
    compileOnly(variantOf(libs.forge.universal.mc1211) { classifier("universal") })
    compileOnly(libs.forge.eventbus.mc1211)
    compileOnly(libs.forge.fmlloader.mc1211)
    compileOnly(libs.forge.fmlcore.mc1211)
    compileOnly(libs.forge.spi.mc1211)
    compileOnly(libs.forge.javafml.mc1211)
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
    archiveBaseName.set("serverspulse-forge-1.21.1")
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
