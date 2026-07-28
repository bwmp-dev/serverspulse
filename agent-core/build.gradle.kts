plugins {
    id("serverspulse.base")
}

dependencies {
    api(project(":agent-api"))
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.snakeyaml)
}
