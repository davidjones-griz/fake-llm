plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "fake-llm"

// The JUnit Jupiter extension, published as com.davidjonesgriz:fake-llm-junit-jupiter.
include(":fake-llm-junit-jupiter")
project(":fake-llm-junit-jupiter").projectDir = file("junit-jupiter")

// The HTTP conformance runner (not published): runs conformance/cases against a server by URL.
include(":fake-llm-conformance")
project(":fake-llm-conformance").projectDir = file("conformance/runner")

// The Testcontainers module, published as com.davidjonesgriz:fake-llm-testcontainers.
include(":fake-llm-testcontainers")
project(":fake-llm-testcontainers").projectDir = file("testcontainers")
