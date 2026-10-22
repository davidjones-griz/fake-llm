import java.util.Properties

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
}

// The fake-llm version under test: -PfakeLlmVersion=..., else the version in ../gradle.properties.
val fakeLlmVersion: String =
    providers.gradleProperty("fakeLlmVersion").orNull
        ?: Properties().apply { file("../gradle.properties").reader().use { load(it) } }.getProperty("version")

repositories {
    // Filled by `./gradlew publishAllPublicationsToBuildLocalRepository` in the repository root.
    maven {
        url = uri("../build/local-repo")
        content { includeGroup("dev.fakellm") }
    }
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("dev.fakellm:fake-llm:$fakeLlmVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
