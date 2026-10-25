package com.davidjonesgriz.fakellm.junit

import com.davidjonesgriz.fakellm.junit.fixtures.AbortedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.AlreadyFailingFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ComposedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ComposedOptOutFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ConcurrentMethodsFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ConcurrentUnservedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ConfiguredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.CounterBalanceFixture
import com.davidjonesgriz.fakellm.junit.fixtures.DoubleRegisteredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.DoubleRegisteredUnservedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ExtraCallFixture
import com.davidjonesgriz.fakellm.junit.fixtures.FIXTURES_PARAMETER
import com.davidjonesgriz.fakellm.junit.fixtures.InheritedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.InheritedOptOutFixture
import com.davidjonesgriz.fakellm.junit.fixtures.InjectionFixture
import com.davidjonesgriz.fakellm.junit.fixtures.InstanceRegisteredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.InstanceRegisteredUnservedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.InterfaceFixture
import com.davidjonesgriz.fakellm.junit.fixtures.IsolationFixture
import com.davidjonesgriz.fakellm.junit.fixtures.JavaFixture
import com.davidjonesgriz.fakellm.junit.fixtures.LenientNoScenarioFixture
import com.davidjonesgriz.fakellm.junit.fixtures.MethodOptOutFixture
import com.davidjonesgriz.fakellm.junit.fixtures.NestedInstanceRegisteredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.NestedOptOutFixture
import com.davidjonesgriz.fakellm.junit.fixtures.NoScenarioFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ParallelFixtureA
import com.davidjonesgriz.fakellm.junit.fixtures.ParallelFixtureB
import com.davidjonesgriz.fakellm.junit.fixtures.ParameterizedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.ParameterizedUnservedFixture
import com.davidjonesgriz.fakellm.junit.fixtures.PerClassInjectionFixture
import com.davidjonesgriz.fakellm.junit.fixtures.PrecedenceFixture
import com.davidjonesgriz.fakellm.junit.fixtures.Sink
import com.davidjonesgriz.fakellm.junit.fixtures.StaticRegisteredFixture
import com.davidjonesgriz.fakellm.junit.fixtures.StrictNoScenarioFixture
import com.davidjonesgriz.fakellm.junit.fixtures.UnknownAfterUnservedFixture
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
import kotlin.test.assertIs
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
        run(ConfiguredFixture::class.java).assertAllPassed(tests = 2)
        assertEquals(listOf(true), Sink.get("configured:lenient"))
        assertEquals(listOf(200), Sink.get("configured:fromDirectory"), "custom.json is only in the scenario directory")
        assertEquals(listOf(200), Sink.get("configured:noScenario"), "lenient answers a call with no scenario loaded")
    }

    @Test
    fun `a static RegisterExtension field takes its configuration from the constructor`() {
        run(StaticRegisteredFixture::class.java).assertAllPassed(tests = 3)
        assertEquals(listOf(200, 200), Sink.get("static:fromDirectory"))
        assertEquals(listOf(200), Sink.get("static:noScenario"))
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
    fun `the annotations are found inside composed annotations and on interfaces`() {
        run(ComposedFixture::class.java, InterfaceFixture::class.java).assertAllPassed(tests = 3)
        assertEquals(listOf(true), Sink.get("composed:lenient"))
        assertEquals(listOf("hello"), Sink.get("composed:classScenario"))
        assertEquals(listOf("refusal"), Sink.get("composed:methodScenario"))
        assertEquals(listOf(true), Sink.get("interface:lenient"))
        assertEquals(listOf("hello"), Sink.get("interface:scenario"))
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
        val failure = results.failures().getValue("a()")
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
        // Only the first two parts, so a pre-release such as 6.2.0-M1 still parses.
        val (major, minor) = version.split(".").take(2).map { it.toInt() }
        assumeTrue(major > 5 || minor >= 12, "JUnit $version has no test_method extension context scope")

        val scoped = mapOf("junit.jupiter.extensions.testinstantiation.extensioncontextscope.default" to "test_method")
        run(InjectionFixture::class.java, PerClassInjectionFixture::class.java, parameters = scoped).assertAllPassed(tests = 4)
        assertEquals(1, Sink.get("injection").toSet().size, "${Sink.get("injection")}")
        assertEquals(1, Sink.get("perClass").toSet().size, "${Sink.get("perClass")}")
    }

    @Test
    fun `a call after the last step fails a test that otherwise passed, naming the call`() {
        val results = run(ExtraCallFixture::class.java)
        results.testEvents().assertStatistics { it.started(1).failed(1) }
        assertEquals(listOf(200, 400), Sink.get("extra:status"))
        val failure = results.failures().getValue("extra(FakeLlmServer)")
        assertIs<AssertionError>(failure)
        assertContains(failure.message.orEmpty(), "call 2: anthropic model 'example-model', no-more-steps")
        assertContains(failure.message.orEmpty(), "scenario 'hello'")
        assertContains(failure.message.orEmpty(), "@FakeLlmAllowUnservedCalls")
    }

    @Test
    fun `a strict no-scenario call fails the test, and a lenient one does not`() {
        val strict = run(StrictNoScenarioFixture::class.java)
        strict.testEvents().assertStatistics { it.started(1).failed(1) }
        assertContains(
            strict
                .failures()
                .values
                .single()
                .message
                .orEmpty(),
            "no-scenario",
        )
        run(LenientNoScenarioFixture::class.java).assertAllPassed(tests = 1)
        assertEquals(listOf(200), Sink.get("lenientNoScenario:status"))
    }

    @Test
    fun `the opt-out covers its method, its class's nested classes, composed annotations and subclasses`() {
        val method = run(MethodOptOutFixture::class.java)
        method.testEvents().assertStatistics { it.started(2).succeeded(1).failed(1) }
        assertEquals(setOf("checked(FakeLlmServer)"), method.failures().keys)
        run(NestedOptOutFixture::class.java).assertAllPassed(tests = 1)
        run(ComposedOptOutFixture::class.java).assertAllPassed(tests = 1)
        run(InheritedOptOutFixture::class.java).assertAllPassed(tests = 1)
    }

    @Test
    fun `only the parameterized invocation that makes an extra call fails`() {
        val results = run(ParameterizedUnservedFixture::class.java)
        results.testEvents().assertStatistics { it.started(2).succeeded(1).failed(1) }
        assertContains(results.failures().keys.single(), "2")
    }

    @Test
    fun `a double registration reports the failure once`() {
        val results = run(DoubleRegisteredUnservedFixture::class.java)
        results.testEvents().assertStatistics { it.started(1).failed(1) }
        val failure = results.failures().values.single()
        assertContains(failure.message.orEmpty(), "no-more-steps")
        assertEquals(0, failure.suppressed.size)
    }

    @Test
    fun `an instance-field registration still checks, in nested classes too`() {
        val results = run(InstanceRegisteredUnservedFixture::class.java)
        results.testEvents().assertStatistics { it.started(2).failed(2) }
        results.failures().values.forEach { assertContains(it.message.orEmpty(), "no-more-steps") }
    }

    @Test
    fun `a test whose scenario fails to load does not fail on an earlier test's calls`() {
        val results = run(UnknownAfterUnservedFixture::class.java)
        results.testEvents().assertStatistics { it.started(2).succeeded(1).failed(1) }
        val failure = results.failures().getValue("b()")
        assertContains(failure.message.orEmpty(), "no-such-scenario")
        assertEquals(0, failure.suppressed.size)
    }

    @Test
    fun `a test rejected by the concurrency guard does not fail on the running test's calls`() {
        Sink.latch = CountDownLatch(2)
        val results = run(ConcurrentUnservedFixture::class.java, parameters = PARALLEL_CLASSES + ("$PARALLEL.mode.default" to "concurrent"))
        results.testEvents().assertStatistics { it.started(2).failed(2) }
        val (rejected, checked) = results.failures().values.partition { it.message.orEmpty().contains("must not run concurrently") }
        assertEquals(0, rejected.single().suppressed.size)
        assertContains(checked.single().message.orEmpty(), "no-more-steps")
    }

    @Test
    fun `a test that already failed keeps its own failure, with the check's suppressed`() {
        val failure = run(AlreadyFailingFixture::class.java).failures().values.single()
        assertEquals("the test's own failure", failure.message)
        assertContains(
            failure.suppressed
                .single()
                .message
                .orEmpty(),
            "no-more-steps",
        )
    }

    @Test
    fun `an aborted test stays aborted`() {
        run(AbortedFixture::class.java).testEvents().assertStatistics { it.started(1).aborted(1).failed(0) }
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
