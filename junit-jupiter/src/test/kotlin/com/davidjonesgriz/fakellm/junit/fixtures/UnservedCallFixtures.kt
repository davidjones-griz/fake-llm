package com.davidjonesgriz.fakellm.junit.fixtures

import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.junit.FakeLlmAllowUnservedCalls
import com.davidjonesgriz.fakellm.junit.FakeLlmExtension
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario
import com.davidjonesgriz.fakellm.junit.FakeLlmTest
import org.junit.AssumptionViolatedException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit

// Fixtures for the check that every call a test makes gets a step. `hello` has one step, so a
// second call to it gets the "no more steps" 400.

/** Two calls to a one-step scenario: the second gets no step, so the test fails. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class ExtraCallFixture {
    @Test
    fun extra(server: FakeLlmServer) {
        Sink.add("extra:status", post(server))
        Sink.add("extra:status", post(server))
    }
}

/** A call with no scenario loaded, in strict mode, fails the test. */
@Fixture
@FakeLlmTest
class StrictNoScenarioFixture {
    @Test
    fun noScenario(server: FakeLlmServer) {
        Sink.add("strictNoScenario:status", post(server))
    }
}

/** In lenient mode the same call gets the canned reply, which counts as served. */
@Fixture
@FakeLlmTest(lenient = true)
class LenientNoScenarioFixture {
    @Test
    fun noScenario(server: FakeLlmServer) {
        Sink.add("lenientNoScenario:status", post(server))
    }
}

/** The opt-out on one method covers that method only. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class MethodOptOutFixture {
    @Test
    @FakeLlmAllowUnservedCalls
    fun allowed(server: FakeLlmServer) = twoCalls(server)

    @Test
    fun checked(server: FakeLlmServer) = twoCalls(server)
}

/** The opt-out on an enclosing class covers its @Nested classes. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
@FakeLlmAllowUnservedCalls
class NestedOptOutFixture {
    @Nested
    inner class Inner {
        @Test
        fun allowed(server: FakeLlmServer) = twoCalls(server)
    }
}

/** A team's own annotation, composed from the opt-out. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@FakeLlmAllowUnservedCalls
annotation class ExpectsExtraCalls

@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class ComposedOptOutFixture {
    @Test
    @ExpectsExtraCalls
    fun allowed(server: FakeLlmServer) = twoCalls(server)
}

@FakeLlmTest
@FakeLlmScenario("hello")
@FakeLlmAllowUnservedCalls
abstract class OptedOutBase

/** The opt-out is inherited from a superclass. */
@Fixture
class InheritedOptOutFixture : OptedOutBase() {
    @Test
    fun allowed(server: FakeLlmServer) = twoCalls(server)
}

/** Only the invocation that makes an extra call fails. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class ParameterizedUnservedFixture {
    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun calls(
        n: Int,
        server: FakeLlmServer,
    ) {
        repeat(n) { post(server) }
    }
}

/**
 * Reads the class's server out of FakeLlmExtension's store, for a fixture that registers the
 * extension twice and so cannot have it injected (two resolvers would compete).
 */
class CaptureServer : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        val holder =
            context.parent
                .orElseThrow()
                .getStore(ExtensionContext.Namespace.create(FakeLlmExtension::class.java))
                .get("server")
        val getter = holder.javaClass.getDeclaredMethod("getServer").apply { isAccessible = true }
        Sink.add("doubleUnserved:server", getter.invoke(holder))
    }
}

/** Registered twice: the failure is reported once, with nothing suppressed. */
@Fixture
@FakeLlmTest
@ExtendWith(CaptureServer::class)
@FakeLlmScenario("hello")
class DoubleRegisteredUnservedFixture {
    @Test
    fun extra() = twoCalls(Sink.get("doubleUnserved:server").single() as FakeLlmServer)

    companion object {
        @JvmField
        @RegisterExtension
        val fakeLlm = FakeLlmExtension()
    }
}

/** An instance field gets a new extension object per test; the check still runs. */
@Fixture
@FakeLlmScenario("hello")
class InstanceRegisteredUnservedFixture {
    @JvmField
    @RegisterExtension
    val fakeLlm = FakeLlmExtension()

    @Test
    fun extra(server: FakeLlmServer) = twoCalls(server)

    @Nested
    inner class Inner {
        @Test
        fun extra(server: FakeLlmServer) = twoCalls(server)
    }
}

/**
 * Test a (opted out) leaves an unserved call in the log; test b's scenario does not exist, so its
 * beforeEach fails and the check must not read a's log.
 */
@Fixture
@FakeLlmTest
@TestMethodOrder(MethodOrderer.MethodName::class)
class UnknownAfterUnservedFixture {
    @Test
    @FakeLlmScenario("hello")
    @FakeLlmAllowUnservedCalls
    fun a(server: FakeLlmServer) = twoCalls(server)

    @Test
    @FakeLlmScenario("no-such-scenario")
    fun b() = Unit
}

/**
 * Two methods run concurrently. The one that gets in makes an extra call and fails the check; the
 * one the guard rejects must fail only with the guard's message.
 */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class ConcurrentUnservedFixture {
    @Test
    fun first(server: FakeLlmServer) = callThenWait(server)

    @Test
    fun second(server: FakeLlmServer) = callThenWait(server)

    private fun callThenWait(server: FakeLlmServer) {
        twoCalls(server)
        Sink.latch.countDown()
        Sink.latch.await(2, TimeUnit.SECONDS)
    }
}

/** The test fails on its own after an extra call: its own failure stays the primary one. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class AlreadyFailingFixture {
    @Test
    fun fails(server: FakeLlmServer) {
        twoCalls(server)
        throw AssertionError("the test's own failure")
    }
}

/** Each test aborts after an extra call: it stays aborted. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class AbortedFixture {
    @Test
    fun aborts(server: FakeLlmServer) {
        twoCalls(server)
        assumeTrue(false, "not this time")
    }

    @Test
    fun abortsTheJUnit4Way(server: FakeLlmServer) {
        twoCalls(server)
        // org.junit.AssumptionViolatedException extends the internal class Jupiter checks for.
        throw AssumptionViolatedException("not this time")
    }

    // The internal class itself, as JUnit 4.11's assumeThat throws it; only its constructors are deprecated.
    @Suppress("DEPRECATION")
    @Test
    fun abortsTheOlderJUnit4Way(server: FakeLlmServer) {
        twoCalls(server)
        throw org.junit.internal.AssumptionViolatedException("not this time")
    }
}

private fun twoCalls(server: FakeLlmServer) {
    post(server)
    post(server)
}
