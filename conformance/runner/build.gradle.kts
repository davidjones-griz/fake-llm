import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// The root build applies these plugins with their versions; this module uses the same ones.
plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(true) }
}

// The runner talks to the server only over HTTP: no dependency on fake-llm's code.
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("com.networknt:json-schema-validator:3.0.8")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val conformanceDir = rootProject.layout.projectDirectory.dir("conformance")

/** The case files and their schema, as task inputs, passed to the tests as absolute paths. */
abstract class CaseFiles : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cases: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schema: RegularFileProperty

    override fun asArguments(): List<String> =
        listOf(
            "-Dfakellm.casesDir=${cases.get().asFile.absolutePath}",
            "-Dfakellm.schema=${schema.get().asFile.absolutePath}",
        )
}

/** The test runtime classpath, for the self-tests that start a stub server in a separate JVM. */
abstract class StubClasspath : CommandLineArgumentProvider {
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    override fun asArguments(): List<String> = listOf("-Dfakellm.stubClasspath=${classpath.asPath}")
}

val target = listOf("fakellm.command", "fakellm.baseUrl").any { providers.gradleProperty(it).isPresent }

tasks.test {
    useJUnitPlatform()
    // Relative paths in -Pfakellm.command (such as build/libs/fake-llm-all.jar) resolve against the
    // repository root, as conformance/README.md writes them.
    workingDir = rootProject.projectDir
    jvmArgumentProviders +=
        objects.newInstance<CaseFiles>().apply {
            cases.set(conformanceDir.dir("cases"))
            schema.set(conformanceDir.file("schema/case.schema.json"))
        }
    jvmArgumentProviders +=
        objects.newInstance<StubClasspath>().apply { classpath.from(sourceSets.test.map { it.runtimeClasspath }) }
    for (name in listOf("fakellm.command", "fakellm.baseUrl", "fakellm.controlToken", "fakellm.failOnSkip")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // ServersTest checks that a started server never sees the runner's own FAKE_LLM_* variables.
    environment("FAKE_LLM_LENIENT", "true")
    environment("FAKE_LLM_INHERITED", "from-the-build")
    if (target) doNotTrackState("the server under test is not a task input, so a run against it must always run")
}

ktlint {
    version.set(providers.gradleProperty("ktlintVersion"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// The same floors as the root build, read from there.
val lineCoverageFloor: Int by rootProject.extra
val branchCoverageFloor: Int by rootProject.extra

kover {
    reports {
        verify {
            rule("Line coverage") { minBound(lineCoverageFloor, CoverageUnit.LINE) }
            rule("Branch coverage") { minBound(branchCoverageFloor, CoverageUnit.BRANCH) }
        }
    }
}
