import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
    `maven-publish`
    id("com.gradleup.shadow") version "8.3.11"
    // Writes the public API to api/fake-llm.api; `check` fails when the code drifts from it.
    // Newer Kotlin Gradle plugins include ABI validation of their own; revisit on a Kotlin upgrade.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
    // Measures test coverage of src/main; `check` fails when it drops below the floors set further down.
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    // Static analysis and formatting; both run as part of `check`. See config/detekt/detekt.yml.
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
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
    // Warnings fail the build in main and test code, so they never pile up.
    compilerOptions { allWarningsAsErrors.set(true) }
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
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    version.set(providers.gradleProperty("ktlintVersion"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
}

// Coverage floors: raise them when coverage rises; never lower one without a reason in the PR.
// The README's Development section quotes these values; update it with them.
val lineCoverageFloor = 95
val branchCoverageFloor = 65

kover {
    reports {
        verify {
            rule("Line coverage") { minBound(lineCoverageFloor, CoverageUnit.LINE) }
            rule("Branch coverage") { minBound(branchCoverageFloor, CoverageUnit.BRANCH) }
        }
    }
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
