import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// The root build applies these plugins with their versions; this module uses the same ones.
plugins {
    kotlin("jvm")
    `maven-publish`
    id("org.jetbrains.kotlinx.kover")
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

// The oldest supported JUnit Jupiter. The extension compiles against it, and the project using the
// extension brings its own JUnit (5.10.5 or newer, including 6.x), so JUnit is compileOnly.
val minimumJupiter = "5.10.5"

dependencies {
    api(project(":"))
    compileOnly("org.junit.jupiter:junit-jupiter-api:$minimumJupiter")

    testImplementation(platform("org.junit:junit-bom:$minimumJupiter"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-testkit")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// The fixtures are test classes that FakeLlmExtensionTest runs itself; Gradle would only report
// them as skipped.
tasks.withType<Test>().configureEach {
    exclude("**/fixtures/**")
}

// The same tests, compiled once against the minimum above, run again on newer JUnit releases. Each
// run resolves the test runtime with that release's BOM enforced, so the whole platform (engine,
// launcher, testkit) matches it.
fun jupiterRuntime(version: String): Configuration =
    configurations
        .create("jupiter${version.replace(".", "")}Runtime") {
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(configurations.testImplementation.get(), configurations.testRuntimeOnly.get())
            attributes.addAllLater(configurations.testRuntimeClasspath.get().attributes)
        }.also { configuration ->
            dependencies.add(configuration.name, dependencies.enforcedPlatform("org.junit:junit-bom:$version"))
        }

for ((taskName, version) in listOf("testJupiter514" to "5.14.4", "testJupiter6" to "6.1.3")) {
    val runtime = jupiterRuntime(version)
    val jupiterTest =
        tasks.register<Test>(taskName) {
            description = "Runs the tests on JUnit Jupiter $version."
            group = "verification"
            testClassesDirs =
                sourceSets.test
                    .get()
                    .output.classesDirs
            classpath = sourceSets.test.get().output + sourceSets.main.get().output + runtime
            useJUnitPlatform()
        }
    tasks.check { dependsOn(jupiterTest) }
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

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set("fake-llm-junit-jupiter")
                description.set(
                    "JUnit Jupiter extension for fake-llm: a per-class server, a scenario per test, and " +
                        "the server injected into tests.",
                )
            }
        }
    }
}
