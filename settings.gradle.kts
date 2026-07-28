pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
    }
}

rootProject.name = "serverspulse-plugin"

include("agent-api")
include("agent-core")
include("platform-bukkit")
include("platform-fabric")
include("platform-forge:v1.20.1")
include("platform-forge:v1.21.1")
include("platform-forge:v1.21.11")
include("platform-forge:v1.16.5")
// include("platform-forge:v1.12.2")  // TODO: requires FG2.3 legacy deobf pipeline
include("platform-neoforge")
include("platform-neoforge:v1.20.6")
