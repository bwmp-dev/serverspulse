import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("serverspulse.platform")
    `maven-publish`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
kotlin { jvmToolchain(25) }
tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_25) }

dependencies {
    implementation(project(":agent-core"))
    compileOnly("net.minestom:minestom:2026.10.07-26.2")
    testImplementation("net.minestom:minestom:2026.10.07-26.2")
    testImplementation(kotlin("test-junit"))
}

tasks.shadowJar {
    archiveBaseName.set("serverspulse-minestom")
    relocate("kotlin", "com.serverspulse.libs.kotlin")
}

publishing {
    publications {
        create<MavenPublication>("minestom") {
            artifactId = "serverspulse-minestom"
            artifact(tasks.shadowJar)
        }
    }
}
