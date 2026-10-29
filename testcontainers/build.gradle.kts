// The root build applies these plugins with their versions; this module uses the same ones.
plugins {
    kotlin("jvm")
    `maven-publish`
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "com.davidjonesgriz"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions { allWarningsAsErrors.set(true) }
}

java {
    withSourcesJar()
    withJavadocJar()
}

val testcontainersVersion = "2.0.5"

dependencies {
    // `api`: FakeLlmContainer extends GenericContainer, and client() returns a FakeLlmClient.
    api(project(":"))
    api("org.testcontainers:testcontainers:$testcontainersVersion")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The default image's tag: this library's version.
tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("**/fake-llm.version") { expand("version" to version) }
}

// `test` runs the tests that need no Docker. The tests tagged "docker" start containers, so they
// run in their own task, against the image named by -Pfakellm.image, which CI builds from the
// same commit. Neither the image nor Docker is needed for `build`.
tasks.test {
    useJUnitPlatform { excludeTags("docker") }
    systemProperty("fakellm.version", project.version.toString())
}

tasks.register<Test>("dockerTest") {
    description = "Runs the tests that start fake-llm's image (-Pfakellm.image=<image>) with Docker."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("docker") }
    // Read here, not in the action, so the task stays compatible with the configuration cache.
    val image = providers.gradleProperty("fakellm.image").orNull
    image?.let { systemProperty("fakellm.image", it) }
    doFirst {
        require(image != null) { "set -Pfakellm.image=<image>, e.g. fake-llm:ci from `docker build -t fake-llm:ci .`" }
    }
    // The tests read their scenario directory relative to this module.
    inputs.dir("src/test/scenarios").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("scenarios")
    // A container's state is outside Gradle's view, so the task always runs.
    outputs.upToDateWhen { false }
}

ktlint {
    version.set(providers.gradleProperty("ktlintVersion"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set("fake-llm-testcontainers")
                description.set(
                    "Testcontainers container for fake-llm's Docker image: health-checked startup, " +
                        "per-provider base URLs, a scenario directory and a control API client. Requires Java 21 or newer.",
                )
            }
        }
    }
}
