plugins {
    id("serverspulse.base")
}

dependencies {
    api(project(":agent-core"))
    compileOnly(libs.spigot.api)
}
