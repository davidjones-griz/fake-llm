package com.davidjonesgriz.fakellm.junit.fixtures

import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.Match
import com.davidjonesgriz.fakellm.ParallelSection
import com.davidjonesgriz.fakellm.Scenario
import com.davidjonesgriz.fakellm.ScenarioEngine
import com.davidjonesgriz.fakellm.Step
import com.davidjonesgriz.fakellm.junit.FakeLlmAllowUnservedCalls
import com.davidjonesgriz.fakellm.junit.FakeLlmAllowUnusedSteps
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario
import com.davidjonesgriz.fakellm.junit.FakeLlmTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Fixtures for the check that every step of a test's scenario served a call. `fail-then-retry`
// has two steps, so a test that makes one call leaves step 2 unused.

/** One call to a two-step scenario: step 2 is unused, so the test fails. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("fail-then-retry")
class MissingCallFixture {
    @Test
    fun missing(server: FakeLlmServer) {
        Sink.add("missing:status", post(server))
    }
}

/** A test that uses every step passes, and so does a test with no scenario. */
@Fixture
@FakeLlmTest
class AllStepsUsedFixture {
    @Test
    @FakeLlmScenario("fail-then-retry")
    fun everyStep(server: FakeLlmServer) {
        post(server)
        post(server)
    }

    @Test
    fun noScenario() = Unit
}

/** The opt-out on one method covers that method only, and on a @Nested class that class. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("fail-then-retry")
class UnusedStepsOptOutFixture {
    @Test
    @FakeLlmAllowUnusedSteps
    fun allowed(server: FakeLlmServer) {
        post(server)
    }

    @Test
    fun checked(server: FakeLlmServer) {
        post(server)
    }

    @Nested
    @FakeLlmAllowUnusedSteps
    inner class Inner {
        @Test
        fun allowed() = Unit
    }
}

/** A test that fails on its own keeps that failure alone: its unused steps are not reported. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("fail-then-retry")
class FailingWithUnusedStepsFixture {
    @Test
    fun fails(): Unit = throw AssertionError("the test's own failure")
}

/** A scenario loaded inside the test is the one checked: two of its three steps are unused. */
@Fixture
@FakeLlmTest
class InlineUnusedStepsFixture {
    @Test
    fun inline(
        engine: ScenarioEngine,
        server: FakeLlmServer,
    ) {
        engine.loadInline(Scenario(name = "three-steps", steps = List(3) { Step(text = "reply ${it + 1}") }))
        post(server)
    }
}

/** Each opt-out turns off its own check only. */
@Fixture
@FakeLlmTest
class OptOutsAreIndependentFixture {
    @Test
    @FakeLlmScenario("fail-then-retry")
    @FakeLlmAllowUnservedCalls
    fun unservedAllowed(server: FakeLlmServer) {
        post(server)
    }

    @Test
    @FakeLlmScenario("hello")
    @FakeLlmAllowUnusedSteps
    fun unusedAllowed(server: FakeLlmServer) {
        post(server)
        post(server)
    }
}

/** A parallel step left partly unused, with a later step unused too: the unused steps are 1 and 3. */
@Fixture
@FakeLlmTest
class NonContiguousUnusedStepsFixture {
    @Test
    fun partly(
        engine: ScenarioEngine,
        server: FakeLlmServer,
    ) {
        val on = { model: String -> Step(text = model, match = Match(model = model)) }
        engine.loadInline(
            Scenario(
                name = "groups",
                steps =
                    listOf(
                        Step(parallel = ParallelSection(listOf(on("a"), on("b")), overlap = true)),
                        Step(parallel = ParallelSection(listOf(on("c")), overlap = true)),
                        Step(text = "after"),
                    ),
            ),
        )
        Sink.add("partly:status", post(server, model = "c"))
    }
}

/** A call that matches none of the steps it could take is unserved; the opt-out allows it. */
@Fixture
@FakeLlmTest
class NoMatchFixture {
    private fun load(engine: ScenarioEngine) =
        engine.loadInline(Scenario(name = "matched", steps = listOf(Step(text = "a", match = Match(model = "a")))))

    @Test
    fun unmatched(
        engine: ScenarioEngine,
        server: FakeLlmServer,
    ) {
        load(engine)
        Sink.add("noMatch:status", post(server, model = "x"))
        post(server, model = "a")
    }

    @Test
    @FakeLlmAllowUnservedCalls
    fun allowed(
        engine: ScenarioEngine,
        server: FakeLlmServer,
    ) {
        load(engine)
        post(server, model = "x")
        post(server, model = "a")
    }
}
