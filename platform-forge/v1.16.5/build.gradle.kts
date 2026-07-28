plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmToolchain(8)
}

val forgeVersion = libs.versions.forge.mc1165.get()
val mcpConfigVersion = "1.16.5-20210115.111550"

val forge165MappedDir = file(
    "${gradle.gradleUserHomeDir}/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/${forgeVersion}_mapped_official_1.16.5"
)
val forge165MappedMainJar = forge165MappedDir.resolve("forge-${forgeVersion}_mapped_official_1.16.5.jar")
val forge165MappedLauncherJar = forge165MappedDir.resolve("forge-${forgeVersion}_mapped_official_1.16.5-launcher.jar")

val srgToOfficialMappings = file(
    "${gradle.gradleUserHomeDir}/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/$mcpConfigVersion/srg_to_official_1.16.5.tsrg"
)

val shade: Configuration = configurations.create("shade") {
    isTransitive = true
}
val reobfTool: Configuration = configurations.create("reobfTool") {
    isTransitive = false
}

dependencies {
    implementation(project(":agent-core"))

    compileOnly(files(forge165MappedMainJar, forge165MappedLauncherJar))
    compileOnly(libs.forge.eventbus.mc1165)
    compileOnly(libs.forge.spi.mc1165)
    compileOnly(libs.brigadier.mc1165)
    compileOnly(libs.log4j.api)

    shade(project(":agent-core"))
    shade(libs.kotlin.stdlib)

    reobfTool(variantOf(libs.forge.autorenamingtool) { classifier("all") })
}

val javaToolchainService = extensions.getByType<JavaToolchainService>()

/**
 * Runs the bundled ForgeGradle 5 build, which populates the shared Gradle
 * cache with the mapped jars and SRG->official mappings this module needs.
 *
 * It is skipped once those files exist, so the cost is paid once per machine
 * rather than on every build. ForgeGradle 5 requires Gradle 7 and a Java 17
 * host JVM, which is why this is a nested build rather than a plugin applied
 * here; both are pinned by the setup project's own wrapper and the toolchain
 * launcher below.
 */
val bootstrapMappedForgeJars by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Generates the official-mapped Forge 1.16.5 jars via the bundled ForgeGradle 5 build"

    val setupDir = rootProject.layout.projectDirectory.dir("platform-forge/v1.16.5-fg5-setup").asFile
    val gradleUserHome = gradle.gradleUserHomeDir
    val mainJar = forge165MappedMainJar
    val launcherJar = forge165MappedLauncherJar
    val mappings = srgToOfficialMappings
    val version = forgeVersion

    // ForgeGradle 5 runs on Gradle 7, which does not support Java 21+.
    val launcher = javaToolchainService.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    }

    onlyIf {
        !(mainJar.exists() && launcherJar.exists() && mappings.exists())
    }

    workingDir = setupDir
    val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"

    doFirst {
        logger.lifecycle(
            "Generating official-mapped Forge $version artifacts with ForgeGradle 5. " +
                "This runs once per machine and takes a few minutes."
        )
        // Point the nested build at the same Gradle home so it writes where
        // this module reads, even when the outer build uses a custom -g.
        environment("JAVA_HOME", launcher.get().metadata.installationPath.asFile.absolutePath)
        commandLine(
            wrapper,
            "--no-daemon",
            "--gradle-user-home", gradleUserHome.absolutePath,
            "-PforgeVersion=$version",
            "generateMappedForgeJars"
        )
    }
}

val verifyReobfInputs by tasks.registering {
    dependsOn(bootstrapMappedForgeJars)

    val mappedDir = forge165MappedDir
    val mainJar = forge165MappedMainJar
    val launcherJar = forge165MappedLauncherJar
    val mappings = srgToOfficialMappings

    doLast {
        val setupHint = """
            |
            |These are produced by the bundled ForgeGradle 5 build in
            |platform-forge/v1.16.5-fg5-setup, which this build runs automatically. If it
            |failed above, run it directly to see why:
            |    cd platform-forge/v1.16.5-fg5-setup && ./gradlew generateMappedForgeJars
            |It needs network access and a Java 17 toolchain. To build the other bands
            |meanwhile:
            |    ./gradlew build -x :platform-forge:v1.16.5:build
        """.trimMargin()

        if (!mainJar.exists() || !launcherJar.exists()) {
            throw GradleException("Missing Forge 1.16.5 mapped jars in ${mappedDir.absolutePath}$setupHint")
        }
        if (!mappings.exists()) {
            throw GradleException("Missing SRG->official mappings at ${mappings.absolutePath}$setupHint")
        }
    }
}

// The mapped jars are on the compile classpath, so they must exist before
// anything compiles -- not just before the jar is assembled.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(verifyReobfInputs)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(verifyReobfInputs)
}

tasks.shadowJar {
    dependsOn(verifyReobfInputs)
    configurations = listOf(shade)
    archiveBaseName.set("serverspulse-forge-1.16.5")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("tmp/shadow"))
    mergeServiceFiles()

    relocate("com.google.gson", "com.serverspulse.libs.gson")
    relocate("okhttp3", "com.serverspulse.libs.okhttp3")
    relocate("okio", "com.serverspulse.libs.okio")
    relocate("org.yaml.snakeyaml", "com.serverspulse.libs.snakeyaml")

    exclude("com/google/errorprone/**")
    exclude("org/intellij/lang/annotations/**")
    exclude("org/jetbrains/annotations/**")
}

val reobfShadowJar by tasks.registering(Exec::class) {
    dependsOn(tasks.shadowJar)
    group = "build"
    description = "Reobfuscates shaded jar from official names to SRG names"

    val inputJar = tasks.shadowJar.flatMap { it.archiveFile }
    val outputJar = layout.buildDirectory.file("libs/serverspulse-forge-1.16.5-${project.version}.jar")
    val mappings = srgToOfficialMappings

    inputs.file(inputJar)
    inputs.file(mappings)
    inputs.files(configurations.compileClasspath)
    inputs.files(reobfTool)
    outputs.file(outputJar)

    doFirst {
        val reobfJar = reobfTool.singleFile
        val libs = configurations.compileClasspath
            .get()
            .files
            .filter { it.exists() && it.isFile }

        outputJar.get().asFile.parentFile.mkdirs()
        val args = mutableListOf(
            "java",
            "-jar",
            reobfJar.absolutePath,
            "--input",
            inputJar.get().asFile.absolutePath,
            "--output",
            outputJar.get().asFile.absolutePath,
            "--map",
            mappings.absolutePath,
            "--reverse"
        )
        libs.forEach { lib ->
            args.add("--lib")
            args.add(lib.absolutePath)
        }
        commandLine(args)
    }
}

tasks.build {
    dependsOn(reobfShadowJar)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") {
        expand(props)
    }
}
