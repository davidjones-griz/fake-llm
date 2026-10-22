plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
    `maven-publish`
    id("com.gradleup.shadow") version "8.3.11"
    // Writes the public API to api/fake-llm.api; `check` fails when the code drifts from it.
    // Newer Kotlin Gradle plugins include ABI validation of their own; revisit on a Kotlin upgrade.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

// The version lives in gradle.properties so examples/ can read the same value.
group = "com.davidjonesgriz"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    // Every public declaration in src/main needs an explicit `public`, so API is never accidental.
    explicitApi()
}

java {
    withSourcesJar()
    withJavadocJar()
}

application {
    mainClass.set("com.davidjonesgriz.fakellm.FakeLlmAppKt")
}

dependencies {
    // `api`: the public scenario model exposes kotlinx-serialization types (KSerializer).
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName.set("fake-llm-all.jar")
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set("fake-llm")
                description.set(
                    "Scenario-driven local stand-in for the Anthropic, OpenAI and Gemini HTTP APIs, " +
                        "for testing LLM-calling code without real model calls.",
                )
                url.set("https://github.com/davidjones-griz/fake-llm")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("davidjones-griz")
                        name.set("David Jones")
                    }
                }
                scm {
                    url.set("https://github.com/davidjones-griz/fake-llm")
                    connection.set("scm:git:https://github.com/davidjones-griz/fake-llm.git")
                    developerConnection.set("scm:git:ssh://git@github.com/davidjones-griz/fake-llm.git")
                }
            }
        }
    }
    repositories {
        // A file repository inside build/, for examples/ and CI to consume the published artifact.
        maven {
            name = "buildLocal"
            url = uri(layout.buildDirectory.dir("local-repo"))
        }
    }
}
