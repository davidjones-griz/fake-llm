import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
    // Writes each module's public API to its api/*.api file; `check` fails when the code drifts from it.
    // Kept over the Kotlin Gradle plugin's own ABI validation, which in Kotlin 2.4 still needs an
    // explicit opt-in (@ExperimentalAbiValidation) and whose DSL is still changing (2.4 removed
    // several earlier parts). Revisit when a Kotlin release makes it stable (#53).
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
    mainClass.set("com.davidjonesgriz.fakellm.FakeLlmMain")
}

dependencies {
    // `api`: the public scenario model exposes kotlinx-serialization types (KSerializer).
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation(kotlin("test"))
    // OpenApiConformanceTest: validates real responses against openapi/fake-llm.yaml (OAS 3.1).
    testImplementation("com.networknt:json-schema-validator:3.0.8")
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml:3.2.1")
}

tasks.test {
    useJUnitPlatform()
    // OpenApiConformanceTest checks every conformance case against the spec.
    inputs.dir("conformance/cases").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("conformanceCases")
    // The scenario JSON Schema and the scenario files the tests check against it and the spec.
    inputs.file("schema/scenario.schema.json").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("scenarioSchema")
    listOf(
        "examples/src/test/resources/scenarios",
        "examples/src/test/scenarios",
        "junit-jupiter/src/test/fixture-scenarios",
        "examples/github-actions/scenarios",
    ).forEach {
        inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("scenarios:$it")
    }
}

// Writes schema/scenario.schema.json from the OpenAPI spec's Scenario schema (see
// ScenarioSchemaGenerator). ScenarioSchemaTest fails until the committed file matches the spec.
tasks.register<JavaExec>("updateScenarioSchema") {
    group = "build"
    description = "Writes schema/scenario.schema.json from the OpenAPI spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.davidjonesgriz.fakellm.ScenarioSchemaGeneratorKt")
    workingDir = projectDir
}

// The OpenAPI document is the language-neutral contract, kept at the repository root; the server
// serves the copy bundled here at GET /openapi. It goes under fake-llm's own resource path, so a
// project's own resources cannot replace it.
tasks.processResources {
    from("openapi") { into("com/davidjonesgriz/fakellm/openapi") }
}

apiValidation {
    // The conformance runner is not published, so it has no public API to track.
    ignoredProjects.add("fake-llm-conformance")
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
// The junit-jupiter module reads the same floors from here.
val lineCoverageFloor = 95
val branchCoverageFloor = 65
extra["lineCoverageFloor"] = lineCoverageFloor
extra["branchCoverageFloor"] = branchCoverageFloor

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
    // Shadow's Kotlin module transformer only sees the duplicates that are let through, so these
    // files skip the default EXCLUDE strategy. Every other path keeps it.
    filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set("fake-llm")
                description.set(
                    "Scenario-driven local stand-in for the Anthropic, OpenAI and Gemini HTTP APIs, " +
                        "for testing LLM-calling code without real model calls. Requires Java 21 or newer.",
                )
            }
        }
    }
}

// Every published module (this one and junit-jupiter/) shares the POM metadata below and the
// build-local repository that examples/ and CI read.
allprojects {
    // -PtestJavaVersion=<major> runs every Test task on that Java version, while the code is still
    // compiled for Java 21; CI's java25 job uses it to test on the current LTS.
    providers.gradleProperty("testJavaVersion").orNull?.let { value ->
        val testJava = value.toIntOrNull() ?: error("testJavaVersion must be a Java major version, e.g. 25")
        tasks.withType<Test>().configureEach {
            javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJava)) })
            doFirst { logger.lifecycle("$path runs on Java ${javaLauncher.get().metadata.javaRuntimeVersion}") }
        }
    }

    pluginManager.withPlugin("maven-publish") {
        configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
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
            repositories {
                // A file repository inside the root build/, for examples/ and CI to consume the published artifacts.
                maven {
                    name = "buildLocal"
                    url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
                }
                // The Maven Central bundle the release workflow zips and uploads (see "Releasing" in README.md).
                maven {
                    name = "centralBundle"
                    url = uri(rootProject.layout.buildDirectory.dir("central-bundle"))
                }
            }
        }

        // Signed only when SIGNING_KEY is set, as in the release workflow; local publishing stays unsigned.
        apply(plugin = "signing")
        val signingKey = System.getenv("SIGNING_KEY")
        if (!signingKey.isNullOrBlank()) {
            configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey, System.getenv("SIGNING_KEY_PASSWORD"))
                sign(the<PublishingExtension>().publications)
            }
        }
    }
}
