plugins {
    id("serverspulse.base")
    id("com.gradleup.shadow")
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()

    relocate("com.google.gson", "com.serverspulse.libs.gson")
    relocate("okhttp3", "com.serverspulse.libs.okhttp3")
    relocate("okio", "com.serverspulse.libs.okio")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
