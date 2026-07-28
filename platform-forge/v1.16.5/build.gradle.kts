plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("com.gradleup.shadow") version "9.3.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmToolchain(8)
}

val forge165MappedDir = file(
    "${gradle.gradleUserHomeDir}/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.16.5-36.2.42_mapped_official_1.16.5"
)
val forge165MappedMainJar = forge165MappedDir.resolve("forge-1.16.5-36.2.42_mapped_official_1.16.5.jar")
val forge165MappedLauncherJar = forge165MappedDir.resolve("forge-1.16.5-36.2.42_mapped_official_1.16.5-launcher.jar")

val srgToOfficialMappings = file(
    "${gradle.gradleUserHomeDir}/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/1.16.5-20210115.111550/srg_to_official_1.16.5.tsrg"
)

val shade: Configuration by configurations.creating {
    isTransitive = true
}
val reobfTool: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(project(":agent-core"))

    compileOnly(files(forge165MappedMainJar, forge165MappedLauncherJar))
    compileOnly("net.minecraftforge:eventbus:4.0.0")
    compileOnly("net.minecraftforge:forgespi:3.2.0")
    compileOnly("com.mojang:brigadier:1.0.18")
    compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")

    shade(project(":agent-core"))
    shade("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")

    reobfTool("net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all")
}

val verifyReobfInputs by tasks.registering {
    doLast {
        if (!forge165MappedMainJar.exists() || !forge165MappedLauncherJar.exists()) {
            throw GradleException("Missing Forge 1.16.5 mapped jars in ${forge165MappedDir.absolutePath}")
        }
        if (!srgToOfficialMappings.exists()) {
            throw GradleException("Missing SRG->official mappings at ${srgToOfficialMappings.absolutePath}")
        }
    }
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
}

val reobfShadowJar by tasks.registering(Exec::class) {
    dependsOn(tasks.shadowJar)
    group = "build"
    description = "Reobfuscates shaded jar from official names to SRG names"

    val inputJar = tasks.shadowJar.flatMap { it.archiveFile }
    val outputJar = layout.buildDirectory.file("libs/serverspulse-forge-1.16.5-${project.version}.jar")

    inputs.file(inputJar)
    inputs.file(srgToOfficialMappings)
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
            srgToOfficialMappings.absolutePath,
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
