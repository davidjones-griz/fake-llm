import java.util.Properties

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

// Shared with the root build: the fake-llm version and the ktlint version.
val rootProperties = Properties().apply { file("../gradle.properties").reader().use { load(it) } }

// The fake-llm version under test: -PfakeLlmVersion=..., else the version in ../gradle.properties.
val fakeLlmVersion: String =
    providers.gradleProperty("fakeLlmVersion").orNull ?: rootProperties.getProperty("version")

repositories {
    // Filled by `./gradlew publishAllPublicationsToBuildLocalRepository` in the repository root.
    maven {
        url = uri("../build/local-repo")
        content { includeGroup("com.davidjonesgriz") }
    }
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("com.davidjonesgriz:fake-llm:$fakeLlmVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    version.set(rootProperties.getProperty("ktlintVersion"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("../config/detekt/detekt.yml"))
}
