package com.davidjonesgriz.fakellm.junit

import com.davidjonesgriz.fakellm.junit.fixtures.ConcurrentMethodsFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ConfiguredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.CounterBalanceFixture
import com.davidjonesgriz.fakellm.junit.fixtures.DoubleRegisteredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.FIXTURES_PARAMETER
import com.davidjonesgriz.fakellm.junit.fixtures.InheritedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.InjectionFixture
import com.davidjonesgriz.fakellm.junit.fixtures.InstanceRegisteredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.IsolationFixture
import com.davidjonesgriz.fakellm.junit.fixtures.JavaFixture
import com.davidjonesgriz.fakellm.junit.fixtures.NestedInstanceRegisteredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.NoScenarioFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ParallelFixtureA
import com.davidjonesgriz.fakellm.junit.fixtures.ParallelFixtureB
import com.davidjonesgriz.fakellm.junit.fixtures.ParameterizedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.PerClassInjectionFixture
import com.davidjonesgriz.fakellm.junit.fixtures.PrecedenceFixture
import com.davidjonesgriz.fakellm.junit.fixtures.Sink
import com.davidjonesgriz.fakellm.junit.fixtures.StaticRegisteredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.UnknownScenarioFixture
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Runs the fixture classes in the fixtures package through the JUnit Platform EngineTestKit, then
 * checks how their tests came out and what they recorded. The same tests run on every supported
 * JUnit version (see the build's testJupiter tasks).
 */
class FakeLlmExtensionTest {
    @BeforeEach
    fun clearSink() = Sink.clear()

    @Test
    fun `injects one server into constructors, BeforeAll, BeforeEach and tests, and closes it after the class`() {
        run(InjectionFixture::class.java).assertAllPassed(tests = 2)
        // beforeAll, then constructor, beforeEach and test for each of the two tests.
        val ports = Sink.get("injection")
        assertEquals(7, ports.size)
        assertEquals(1, ports.toSet().size, "every injection point gets the same server: $ports")
        assertEquals(listOf(200, 200), Sink.get("injection:status"))
        assertClosed(ports.first() as Int)
    }

    @Test
    fun `injects into the constructor and a BeforeAll method under PER_CLASS`() {
        run(PerClassInjectionFixture::class.java).assertAllPassed(tests = 2)
        val ports = Sink.get("perClass")
        assertEquals(4, ports.size)
        assertEquals(1, ports.toSet().size, "every injection point gets the same server: $ports")
        assertClosed(ports.first() as Int)
    }

    @Test
    fun `loads the nearest scenario before the user's BeforeEach, and Nested classes share the server`() {
        run(PrecedenceFixture::class.java).assertAllPassed(tests = 5)
        assertEquals(listOf("hello"), Sink.get("precedence:classScenario"))
        assertEquals(listOf("refusal"), Sink.get("precedence:methodScenario"))
        assertEquals(listOf("hello"), Sink.get("precedence:enclosingScenario"))
        assertEquals(listOf("fail-then-retry"), Sink.get("precedence:nestedClassScenario"))
        assertEquals(listOf("thinking-stream"), Sink.get("precedence:nestedMethodScenario"))
        assertEquals(listOf("hello"), Sink.get("precedence:beforeEach:classScenario"))
        assertEquals(listOf("refusal"), Sink.get("precedence:beforeEach:methodScenario"))
        val ports = Sink.get("precedence:port")
        assertEquals(2, ports.size)
        assertEquals(1, ports.toSet().size, "the nested class uses the outer class's server: $ports")
    }

    @Test
    fun `a test with no scenario annotation runs with none loaded, even after a BeforeAll load`() {
        run(NoScenarioFixture::class.java).assertAllPassed(tests = 1)
        assertEquals(listOf(null), Sink.get("noScenario"))
        assertEquals(listOf(400), Sink.get("noScenario:status"), "strict by default: no scenario means HTTP 400")
    }

    @Test
    fun `each test starts with fresh step counters and an empty call log`() {
        run(IsolationFixture::class.java).assertAllPassed(tests = 3)
        assertEquals(listOf(500, 500, 500), Sink.get("isolation:status"))
        assertEquals(listOf(1, 1, 1), Sink.get("isolation:calls"))
    }

    @Test
    fun `FakeLlmTest's scenarioDirectory and lenient configure the server`() {
        run(ConfiguredFixture::class.java).assertAllPassed(tests = 1)
        assertEquals(listOf(true), Sink.get("configured:lenient"))
        assertEquals(listOf(200), Sink.get("configured:matched"), "custom.json is only in the scenario directory")
        assertEquals(listOf(200), Sink.get("configured:unmatched"), "lenient answers a request no rule matches")
    }

    @Test
    fun `a static RegisterExtension field takes its configuration from the constructor`() {
        run(StaticRegisteredFixture::class.java).assertAllPassed(tests = 2)
        assertEquals(listOf(200, 200), Sink.get("static:matched"))
        assertEquals(listOf(200, 200), Sink.get("static:unmatched"))
        assertEquals(1, Sink.get("static:port").toSet().size)
    }

    @Test
    fun `an instance RegisterExtension field shares one server and still sets up every test`() {
        run(InstanceRegisteredFixture::class.java).assertAllPassed(tests = 3)
        assertEquals(1, Sink.get("instance:port").toSet().size, "one server for the class: ${Sink.get("instance:port")}")
        assertEquals(listOf(500, 500, 500), Sink.get("instance:status"))
        assertEquals(listOf(1, 1, 1), Sink.get("instance:calls"))
    }

    @Test
    fun `a Nested class under an outer instance field gets the server in its constructor and a fresh scenario`() {
        run(NestedInstanceRegisteredFixture::class.java).assertAllPassed(tests = 2)
        assertEquals(1, Sink.get("nestedInstance:port").toSet().size)
        assertEquals(listOf(500, 500), Sink.get("nestedInstance:status"))
        assertEquals(listOf(1, 1), Sink.get("nestedInstance:calls"))
    }

    @Test
    fun `registering the extension twice does the per-test work once`() {
        run(DoubleRegisteredFixture::class.java).assertAllPassed(tests = 2)
    }

    @Test
    fun `a subclass inherits FakeLlmTest and FakeLlmScenario`() {
        run(InheritedFixture::class.java).assertAllPassed(tests = 1)
        assertEquals(listOf("hello"), Sink.get("inherited"))
    }

    @Test
    fun `Java code uses the annotations the same way`() {
        run(JavaFixture::class.java).assertAllPassed(tests = 1)
        assertEquals(listOf("hello"), Sink.get("java"))
        assertEquals(listOf(200), Sink.get("java:status"))
    }

    @Test
    fun `an unknown scenario fails only its own test, and the server still closes`() {
        val results = run(UnknownScenarioFixture::class.java)
        results.testEvents().assertStatistics { it.started(2).succeeded(1).failed(1) }
        val failure = results.failures().getValue("unknown()")
        assertContains(failure.message.orEmpty(), "no-such-scenario")
        assertClosed(Sink.get("unknown:port").single() as Int)
    }

    @Test
    fun `parallel classes each get their own server`() {
        Sink.latch = CountDownLatch(2)
        run(ParallelFixtureA::class.java, ParallelFixtureB::class.java, parameters = PARALLEL_CLASSES)
            .assertAllPassed(tests = 2)
        assertEquals(listOf(200, 200), Sink.get("parallel:status"))
        assertEquals(listOf(1, 1), Sink.get("parallel:calls"), "each class's log holds only its own call")
        val ports = Sink.get("parallel:port")
        assertNotEquals(ports[0], ports[1])
    }

    @Test
    fun `a second concurrent test in one class fails with how to configure parallel execution`() {
        Sink.latch = CountDownLatch(2)
        val results = run(ConcurrentMethodsFixture::class.java, parameters = PARALLEL_CLASSES + ("$PARALLEL.mode.default" to "concurrent"))
        results.testEvents().assertStatistics { it.started(2).succeeded(1).failed(1) }
        val failure = results.failures().values.single()
        assertContains(failure.message.orEmpty(), "must not run concurrently")
        assertContains(failure.message.orEmpty(), "junit.jupiter.execution.parallel.mode.default=same_thread")
    }

    @Test
    fun `a test that never reached the extension's beforeEach does not unbalance the guard`() {
        val results = run(CounterBalanceFixture::class.java)
        results.testEvents().assertStatistics { it.started(3).succeeded(2).failed(1) }
        assertEquals("failing before fake-llm", results.failures().getValue("a()").message)
    }

    @Test
    fun `each parameterized invocation gets a fresh scenario`() {
        run(ParameterizedFixture::class.java).assertAllPassed(tests = 3)
        assertEquals(listOf(500, 500, 500), Sink.get("parameterized:status"))
        assertEquals(listOf(1, 1, 1), Sink.get("parameterized:calls"))
    }

    @Test
    fun `with extension contexts scoped to the test method, the server is still one per class`() {
        // The parameter exists from JUnit 5.12; older versions ignore it, so the run proves nothing there.
        val version =
            assertNotNull(
                ExtensionContext::class.java.`package`.implementationVersion,
                "the JUnit Jupiter version is unknown, so this test cannot tell whether it applies",
            )
        val (major, minor) = version.split(".").map { it.toInt() }
        assumeTrue(major > 5 || minor >= 12, "JUnit $version has no test_method extension context scope")

        val scoped = mapOf("junit.jupiter.extensions.testinstantiation.extensioncontextscope.default" to "test_method")
        run(InjectionFixture::class.java, PerClassInjectionFixture::class.java, parameters = scoped).assertAllPassed(tests = 4)
        assertEquals(1, Sink.get("injection").toSet().size, "${Sink.get("injection")}")
        assertEquals(1, Sink.get("perClass").toSet().size, "${Sink.get("perClass")}")
    }

    private fun run(
        vararg classes: Class<*>,
        parameters: Map<String, String> = emptyMap(),
    ): EngineExecutionResults =
        EngineTestKit
            .engine("junit-jupiter")
            .configurationParameter(FIXTURES_PARAMETER, "true")
            .configurationParameters(parameters)
            .selectors(*classes.map { selectClass(it) }.toTypedArray())
            .execute()

    private fun EngineExecutionResults.assertAllPassed(tests: Int) {
        assertEquals(emptyMap(), failures(), "failed tests")
        containerEvents().assertStatistics { it.failed(0) }
        testEvents().assertStatistics { it.started(tests.toLong()).succeeded(tests.toLong()) }
    }

    private fun EngineExecutionResults.failures(): Map<String, Throwable> =
        testEvents()
            .failed()
            .stream()
            .toList()
            .associate { event ->
                event.testDescriptor.displayName to
                    event.getRequiredPayload(TestExecutionResult::class.java).throwable.get()
            }

    private fun assertClosed(port: Int) {
        assertFailsWith<ConnectException>("port $port still accepts connections") {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1000
        const val PARALLEL = "junit.jupiter.execution.parallel"
        val PARALLEL_CLASSES =
            mapOf(
                "$PARALLEL.enabled" to "true",
                "$PARALLEL.mode.default" to "same_thread",
                "$PARALLEL.mode.classes.default" to "concurrent",
                "$PARALLEL.config.strategy" to "fixed",
                "$PARALLEL.config.fixed.parallelism" to "4",
            )
    }
}
