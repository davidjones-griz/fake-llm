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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation("com.davidjonesgriz:fake-llm:$fakeLlmVersion")
    testImplementation("com.davidjonesgriz:fake-llm-junit-jupiter:$fakeLlmVersion")
    // The extension supports JUnit Jupiter 5.10.5 and later; kotlin("test") alone brings in 5.10.1.
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// The Java tests in src/test/java use fake-llm as a Java project would. javac's lint warnings fail
// the build, as compiler warnings do in the Kotlin code.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// -PtestJavaVersion=<major> runs the tests on that Java version, while the code is still
// compiled for Java 21. A copy of the block in the root build.gradle.kts (examples/ is a
// separate build); keep the two in sync.
providers.gradleProperty("testJavaVersion").orNull?.let { value ->
    val testJava = value.toIntOrNull() ?: error("testJavaVersion must be a Java major version, e.g. 25")
    tasks.withType<Test>().configureEach {
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJava)) })
        doFirst { logger.lifecycle("$path runs on Java ${javaLauncher.get().metadata.javaRuntimeVersion}") }
    }
}

ktlint {
    version.set(rootProperties.getProperty("ktlintVersion"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("../config/detekt/detekt.yml"))
}
