plugins {
    base
}

allprojects {
    group = property("group") as String
    version = property("version") as String

    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
    }
}

// Ship the Apache-2.0 licence and attribution notice inside every jar we build.
// This lives here rather than in the `serverspulse.base` convention because most
// platform modules declare their plugins directly and never apply that convention.
subprojects {
    tasks.withType<Jar>().configureEach {
        from(rootProject.layout.projectDirectory.file("LICENSE")) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
        from(rootProject.layout.projectDirectory.file("NOTICE")) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}

val consolidatedArtifactsDir = layout.buildDirectory.dir("artifacts")
val releaseVersion = project.version.toString()

tasks.register<Sync>("collectPlatformArtifacts") {
    group = "build"
    description = "Collects platform plugin jars into one folder"

    dependsOn(
        ":platform-bukkit:shadowJar",
        ":platform-velocity:shadowJar",
        ":platform-bungee:shadowJar",
        ":platform-fabric:remapJar",
        ":platform-forge:v1.20.1:reobfShadowJar",
        ":platform-forge:v1.21.1:shadowJar",
        ":platform-forge:v1.21.11:shadowJar",
        ":platform-forge:v26.2:shadowJar",
        ":platform-forge:v1.16.5:reobfShadowJar",
        // ":platform-forge:v1.12.2:reobfShadowJar",  // TODO: disabled pending FG2.3 setup
        ":platform-neoforge:shadowJar",
        ":platform-neoforge:v1.20.6:shadowJar",
        ":platform-neoforge:v26.1.2:shadowJar"
    )

    into(consolidatedArtifactsDir)

    from(project(":platform-bukkit").layout.buildDirectory.dir("libs")) {
        include("serverspulse-bukkit-${releaseVersion}.jar")
    }
    from(project(":platform-velocity").layout.buildDirectory.dir("libs")) {
        include("serverspulse-velocity-${releaseVersion}.jar")
    }
    // One artifact for BungeeCord and Waterfall: they share the API, and the
    // agent reports which of the two it is running on at runtime.
    from(project(":platform-bungee").layout.buildDirectory.dir("libs")) {
        include("serverspulse-bungee-${releaseVersion}.jar")
    }
    from(project(":platform-fabric").layout.buildDirectory.dir("libs")) {
        include("serverspulse-fabric-${releaseVersion}.jar")
    }
    from(project(":platform-forge:v1.20.1").layout.buildDirectory.dir("libs")) {
        include("serverspulse-forge-1.20.1-${releaseVersion}.jar")
    }
    from(project(":platform-forge:v1.21.1").layout.buildDirectory.dir("libs")) {
        include("serverspulse-forge-1.21.1-${releaseVersion}.jar")
    }
    from(project(":platform-forge:v1.21.11").layout.buildDirectory.dir("libs")) {
        include("serverspulse-forge-1.21.11-${releaseVersion}.jar")
    }
    from(project(":platform-forge:v26.2").layout.buildDirectory.dir("libs")) {
        include("serverspulse-forge-26.2-${releaseVersion}.jar")
    }
    from(project(":platform-forge:v1.16.5").layout.buildDirectory.dir("libs")) {
        include("serverspulse-forge-1.16.5-${releaseVersion}.jar")
    }
    // from(project(":platform-forge:v1.12.2").layout.buildDirectory.dir("libs")) {  // TODO: disabled
    //     include("serverspulse-forge-1.12.2-*.jar")
    // }
    from(project(":platform-neoforge").layout.buildDirectory.dir("libs")) {
        include("serverspulse-neoforge-1.21.1-${releaseVersion}.jar")
    }
    from(project(":platform-neoforge:v1.20.6").layout.buildDirectory.dir("libs")) {
        include("serverspulse-neoforge-1.20.6-${releaseVersion}.jar")
    }
    from(project(":platform-neoforge:v26.1.2").layout.buildDirectory.dir("libs")) {
        include("serverspulse-neoforge-26.1.2-${releaseVersion}.jar")
    }
}

tasks.named("build") {
    dependsOn("collectPlatformArtifacts")
}
