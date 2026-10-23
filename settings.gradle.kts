plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "fake-llm"

// The JUnit Jupiter extension, published as com.davidjonesgriz:fake-llm-junit-jupiter.
include(":fake-llm-junit-jupiter")
project(":fake-llm-junit-jupiter").projectDir = file("junit-jupiter")
