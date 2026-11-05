import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("serverspulse.platform")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

repositories { mavenLocal() }

kotlin {
    jvmToolchain(25)
    sourceSets.main { kotlin.srcDir("../src/main/kotlin") }
}
sourceSets.main { resources.srcDir("../src/main/resources") }

dependencies {
    implementation(project(":agent-core"))
    compileOnly("com.velocitypowered:velocity-api:4.3.1")
}

tasks.shadowJar {
    archiveBaseName.set("serverspulse-velocity-v4")
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
