package com.davidjonesgriz.fakellm.junit.fixtures

import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.junit.FakeLlmExtension
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario
import com.davidjonesgriz.fakellm.junit.FakeLlmTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path

/**
 * One test per fixture scenario, each recording that it ran: which ones run is what the tag
 * selection (`fakellm.tags.include` and `fakellm.tags.exclude`) decides.
 */
@Fixture
@FakeLlmTest(scenarioDirectory = "src/test/fixture-scenarios")
class TagSelectionFixture {
    @BeforeEach
    fun before() {
        Sink.add("tags:beforeEach", true)
    }

    @Test
    @FakeLlmScenario("tagged-malformed")
    fun malformed(server: FakeLlmServer) = ran("malformed", server)

    @Test
    @FakeLlmScenario("tagged-malformed-slow")
    fun malformedSlow(server: FakeLlmServer) = ran("malformedSlow", server)

    @Test
    @FakeLlmScenario("tagged-smoke")
    fun smoke(server: FakeLlmServer) = ran("smoke", server)

    @Test
    @FakeLlmScenario("tagged-uppercase")
    fun uppercase(server: FakeLlmServer) = ran("uppercase", server)

    @Test
    @FakeLlmScenario("untagged")
    fun untagged(server: FakeLlmServer) = ran("untagged", server)

    /** No scenario: the selection doesn't apply to it. */
    @Test
    fun noScenario() {
        Sink.add("tags:ran", "noScenario")
    }

    private fun ran(
        name: String,
        server: FakeLlmServer,
    ) {
        Sink.add("tags:ran", name)
        post(server)
    }
}

/** A class-level scenario, and a nested class that inherits it: both are selected by its tags. */
@Fixture
@FakeLlmTest(scenarioDirectory = "src/test/fixture-scenarios")
@FakeLlmScenario("tagged-smoke")
class ClassLevelTagSelectionFixture {
    @Test
    fun outer(server: FakeLlmServer) {
        Sink.add("tags:ran", "outer")
        post(server)
    }

    @Nested
    inner class Inner {
        @Test
        @FakeLlmScenario("tagged-malformed")
        fun innerMalformed(server: FakeLlmServer) {
            Sink.add("tags:ran", "innerMalformed")
            post(server)
        }

        @Test
        fun innerInherited(server: FakeLlmServer) {
            Sink.add("tags:ran", "innerInherited")
            post(server)
        }
    }
}

/** The extension registered on an instance field, with its own scenario directory. */
@Fixture
class RegisteredTagSelectionFixture {
    @JvmField
    @RegisterExtension
    val fakeLlm = FakeLlmExtension(Path.of("src/test/fixture-scenarios"))

    @Test
    @FakeLlmScenario("tagged-malformed")
    fun registeredMalformed(server: FakeLlmServer) {
        Sink.add("tags:ran", "registeredMalformed")
        post(server)
    }

    @Test
    @FakeLlmScenario("untagged")
    fun registeredUntagged(server: FakeLlmServer) {
        Sink.add("tags:ran", "registeredUntagged")
        post(server)
    }
}

/** A scenario that doesn't exist still fails the test, selection or not, with the loader's message. */
@Fixture
@FakeLlmTest
class UnknownScenarioTagSelectionFixture {
    @Test
    @FakeLlmScenario("no-such-scenario-for-tags")
    fun unknown() {
        Sink.add("tags:ran", "unknown")
    }
}
