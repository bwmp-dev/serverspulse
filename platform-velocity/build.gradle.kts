import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("serverspulse.platform")
}

// The velocity-api module metadata declares a Java 17 consumer requirement, and
// every Velocity 3 proxy runs on 17 or newer anyway. The Java 8 floor the rest
// of the build keeps exists for legacy Minecraft servers, which a proxy is not.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":agent-core"))
    compileOnly(libs.velocity.api)
}

tasks.shadowJar {
    archiveBaseName.set("serverspulse-velocity")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "description" to (project.property("description") as String)
    )
    inputs.properties(props)
    filesMatching("velocity-plugin.json") {
        expand(props)
    }
}
