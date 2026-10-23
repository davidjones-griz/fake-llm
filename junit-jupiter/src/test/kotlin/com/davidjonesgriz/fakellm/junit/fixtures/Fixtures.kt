package com.davidjonesgriz.fakellm.junit.fixtures

import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.ScenarioEngine
import com.davidjonesgriz.fakellm.junit.FakeLlmExtension
import com.davidjonesgriz.fakellm.junit.FakeLlmScenario
import com.davidjonesgriz.fakellm.junit.FakeLlmTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertSame

// Fixture classes for FakeLlmExtensionTest. Each records what it saw in Sink under its own name;
// the test runs them through the EngineTestKit and checks those records and the run's outcome.

private const val LATCH_SECONDS = 10L

/** Injection everywhere JUnit resolves parameters, under the default per-method lifecycle. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class InjectionFixture(
    constructorServer: FakeLlmServer,
) {
    init {
        Sink.add("injection", constructorServer.port)
    }

    @BeforeEach
    fun beforeEach(server: FakeLlmServer) {
        Sink.add("injection", server.port)
    }

    @Test
    fun first(
        server: FakeLlmServer,
        engine: ScenarioEngine,
    ) = answer(server, engine)

    @Test
    fun second(
        server: FakeLlmServer,
        engine: ScenarioEngine,
    ) = answer(server, engine)

    private fun answer(
        server: FakeLlmServer,
        engine: ScenarioEngine,
    ) {
        assertSame(server.engine, engine)
        Sink.add("injection", server.port)
        Sink.add("injection:status", post(server))
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll(server: FakeLlmServer) {
            Sink.add("injection", server.port)
        }
    }
}

/** Under PER_CLASS the instance, and so the constructor's server, exists before beforeAll runs. */
@Fixture
@FakeLlmTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerClassInjectionFixture(
    constructorServer: FakeLlmServer,
) {
    init {
        Sink.add("perClass", constructorServer.port)
    }

    @BeforeAll
    fun beforeAll(server: FakeLlmServer) {
        Sink.add("perClass", server.port)
    }

    @Test
    fun first(server: FakeLlmServer) {
        Sink.add("perClass", server.port)
    }

    @Test
    fun second(server: FakeLlmServer) {
        Sink.add("perClass", server.port)
    }
}

/** Which scenario each test, and the user's own @BeforeEach, sees; and @Nested sharing the server. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class PrecedenceFixture {
    @BeforeEach
    fun beforeEach(
        engine: ScenarioEngine,
        info: TestInfo,
    ) {
        Sink.add("precedence:beforeEach:${info.testMethod.get().name}", engine.currentScenarioName())
    }

    @Test
    fun classScenario(server: FakeLlmServer) {
        Sink.add("precedence:classScenario", server.engine.currentScenarioName())
        Sink.add("precedence:port", server.port)
    }

    @Test
    @FakeLlmScenario("refusal")
    fun methodScenario(engine: ScenarioEngine) {
        Sink.add("precedence:methodScenario", engine.currentScenarioName())
    }

    @Nested
    inner class Inherits {
        @Test
        fun enclosingScenario(server: FakeLlmServer) {
            Sink.add("precedence:enclosingScenario", server.engine.currentScenarioName())
            Sink.add("precedence:port", server.port)
        }
    }

    @Nested
    @FakeLlmScenario("fail-then-retry")
    inner class Overrides {
        @Test
        fun nestedClassScenario(engine: ScenarioEngine) {
            Sink.add("precedence:nestedClassScenario", engine.currentScenarioName())
        }

        @Test
        @FakeLlmScenario("thinking-stream")
        fun nestedMethodScenario(engine: ScenarioEngine) {
            Sink.add("precedence:nestedMethodScenario", engine.currentScenarioName())
        }
    }
}

/** No @FakeLlmScenario anywhere: a scenario loaded in @BeforeAll is unloaded before the test. */
@Fixture
@FakeLlmTest
class NoScenarioFixture {
    @Test
    fun unloaded(server: FakeLlmServer) {
        Sink.add("noScenario", server.engine.currentScenarioName())
        Sink.add("noScenario:status", post(server))
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll(engine: ScenarioEngine) {
            engine.load("hello")
        }
    }
}

/**
 * fail-then-retry answers a caller's first request with HTTP 500 and the next with 200, so each
 * test sees 500 only if the step counters were reset, and one call only if the log was cleared.
 */
@Fixture
@FakeLlmTest
@FakeLlmScenario("fail-then-retry")
@TestMethodOrder(MethodOrderer.MethodName::class)
class IsolationFixture {
    @Test
    fun a(server: FakeLlmServer) = record(server)

    @Test
    fun b(server: FakeLlmServer) = record(server)

    @Test
    fun c(server: FakeLlmServer) = record(server)

    private fun record(server: FakeLlmServer) {
        Sink.add("isolation:status", post(server))
        Sink.add("isolation:calls", server.engine.calls().size)
    }
}

/** scenarioDirectory finds a scenario that is not on the classpath; lenient answers unmatched requests. */
@Fixture
@FakeLlmTest(scenarioDirectory = "src/test/fixture-scenarios", lenient = true)
@FakeLlmScenario("custom")
class ConfiguredFixture {
    @Test
    fun configured(server: FakeLlmServer) {
        Sink.add("configured:lenient", server.engine.lenient)
        Sink.add("configured:matched", post(server, model = "custom-model"))
        Sink.add("configured:unmatched", post(server))
    }
}

/** The same configuration through a static @RegisterExtension field. */
@Fixture
class StaticRegisteredFixture {
    @Test
    @FakeLlmScenario("custom")
    fun first(server: FakeLlmServer) = record(server)

    @Test
    @FakeLlmScenario("custom")
    fun second(server: FakeLlmServer) = record(server)

    private fun record(server: FakeLlmServer) {
        Sink.add("static:port", server.port)
        Sink.add("static:matched", post(server, model = "custom-model"))
        Sink.add("static:unmatched", post(server))
    }

    companion object {
        @JvmField
        @RegisterExtension
        val fakeLlm = FakeLlmExtension(Path.of("src/test/fixture-scenarios"), lenient = true)
    }
}

/**
 * An instance field: JUnit makes a new extension object for every test instance, and none of them
 * gets beforeAll. Each test must still find the class's one server, the scenario loaded, and the
 * log and counters fresh.
 */
@Fixture
@FakeLlmScenario("fail-then-retry")
@TestMethodOrder(MethodOrderer.MethodName::class)
class InstanceRegisteredFixture {
    @JvmField
    @RegisterExtension
    val fakeLlm = FakeLlmExtension()

    @Test
    fun a(server: FakeLlmServer) = record(server)

    @Test
    fun b(server: FakeLlmServer) = record(server)

    @Test
    fun c(server: FakeLlmServer) = record(server)

    private fun record(server: FakeLlmServer) {
        Sink.add("instance:port", server.port)
        Sink.add("instance:status", post(server))
        Sink.add("instance:calls", server.engine.calls().size)
    }
}

/** An outer instance field, no outer tests, and the server injected into the nested class's constructor. */
@Fixture
@FakeLlmScenario("fail-then-retry")
class NestedInstanceRegisteredFixture {
    @JvmField
    @RegisterExtension
    val fakeLlm = FakeLlmExtension()

    @Nested
    @TestMethodOrder(MethodOrderer.MethodName::class)
    inner class Inner(
        private val server: FakeLlmServer,
    ) {
        @Test
        fun a() = record()

        @Test
        fun b() = record()

        private fun record() {
            Sink.add("nestedInstance:port", server.port)
            Sink.add("nestedInstance:status", post(server))
            Sink.add("nestedInstance:calls", server.engine.calls().size)
        }
    }
}

/**
 * Registered twice, with no parameters to resolve. If both registrations did the per-test work,
 * the second would count the test in again and the concurrency guard would fail it.
 */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class DoubleRegisteredFixture {
    @Test
    fun a() = Unit

    @Test
    fun b() = Unit

    companion object {
        @JvmField
        @RegisterExtension
        val fakeLlm = FakeLlmExtension()
    }
}

abstract class FakeLlmBase

@FakeLlmTest
@FakeLlmScenario("hello")
abstract class AnnotatedBase : FakeLlmBase()

/** Inherits @FakeLlmTest and @FakeLlmScenario from its superclass. */
@Fixture
class InheritedFixture : AnnotatedBase() {
    @Test
    fun inherited(server: FakeLlmServer) {
        Sink.add("inherited", server.engine.currentScenarioName())
    }
}

/** One scenario that does not exist; the class's other test still runs. */
@Fixture
@FakeLlmTest
class UnknownScenarioFixture {
    @Test
    @FakeLlmScenario("no-such-scenario")
    fun unknown() = Unit

    @Test
    fun known(server: FakeLlmServer) {
        Sink.add("unknown:port", server.port)
    }
}

/** Two classes, each holding its test open until the other's is running too. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class ParallelFixtureA {
    @Test
    fun concurrent(server: FakeLlmServer) = meetAndRecord("parallel", server)
}

@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class ParallelFixtureB {
    @Test
    fun concurrent(server: FakeLlmServer) = meetAndRecord("parallel", server)
}

private fun meetAndRecord(
    key: String,
    server: FakeLlmServer,
) {
    Sink.add("$key:status", post(server))
    Sink.latch.countDown()
    check(Sink.latch.await(LATCH_SECONDS, TimeUnit.SECONDS)) { "the other class's test never started" }
    // Both requests have been made by now; with a shared server this would see two calls.
    Sink.add("$key:calls", server.engine.calls().size)
    Sink.add("$key:port", server.port)
}

/** Two methods of one class run concurrently: one must be rejected. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("hello")
class ConcurrentMethodsFixture {
    @Test
    fun first() = waitForTheOther()

    @Test
    fun second() = waitForTheOther()

    // The rejected test never gets here, so the other waits out the timeout and then passes.
    private fun waitForTheOther() {
        Sink.latch.countDown()
        Sink.latch.await(2, TimeUnit.SECONDS)
    }
}

class FailingBeforeEach : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        check(context.requiredTestMethod.name != "a") { "failing before fake-llm" }
    }
}

/**
 * Test a fails in an extension that runs before FakeLlmExtension's beforeEach, so that test is never
 * counted in; its afterEach must not count it out. If it did, b and c would each see a count other
 * than one and fail the guard.
 */
@Fixture
@ExtendWith(FailingBeforeEach::class, FakeLlmExtension::class)
@FakeLlmScenario("hello")
@TestMethodOrder(MethodOrderer.MethodName::class)
class CounterBalanceFixture {
    @Test
    fun a() = Unit

    @Test
    fun b() = Unit

    @Test
    fun c() = Unit
}

/** Each invocation of a parameterized test is a test of its own: a fresh scenario each time. */
@Fixture
@FakeLlmTest
@FakeLlmScenario("fail-then-retry")
class ParameterizedFixture {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun invocation(
        @Suppress("UNUSED_PARAMETER") n: Int,
        server: FakeLlmServer,
    ) {
        Sink.add("parameterized:status", post(server))
        Sink.add("parameterized:calls", server.engine.calls().size)
    }
}
