package com.davidjonesgriz.fakellm.junit

import com.davidjonesgriz.fakellm.CallRecord
import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.ScenarioEngine
import com.davidjonesgriz.fakellm.startFakeLlmServer
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.platform.commons.support.AnnotationSupport
import org.opentest4j.TestAbortedException
import java.lang.reflect.AnnotatedElement
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(FakeLlmExtension::class.java)
private const val SERVER = "server"
private const val HANDLED = "handled:"
private const val ENTERED = "entered:"
private const val CHECK = "check:"
private val UNSERVED = setOf("no-more-steps", "no-scenario")
private val INJECTABLE = setOf(FakeLlmServer::class.java, ScenarioEngine::class.java)
private const val CONCURRENT_TESTS =
    "Tests in one class share one fake-llm server, so they must not run concurrently. With JUnit's " +
        "parallel execution, set junit.jupiter.execution.parallel.mode.default=same_thread and " +
        "junit.jupiter.execution.parallel.mode.classes.default=concurrent."

/**
 * JUnit Jupiter extension that runs a fake-llm server for a test class. Usually applied with
 * [FakeLlmTest]; it can also be registered with `@RegisterExtension`, in which case
 * [scenarioDirectory] and [lenient] configure the server (a [FakeLlmTest] on the class takes
 * precedence). Register it once per class: two registrations both resolve parameters, which JUnit
 * rejects.
 *
 * One server serves the whole class and its `@Nested` classes. It starts on first use and stops
 * when the class finishes. Before each test the [FakeLlmScenario] that applies is loaded, which
 * starts it over (empty call and exchange logs, the next call gets step 1); a test with none runs after
 * [ScenarioEngine.unload]. `FakeLlmServer` and `ScenarioEngine` parameters are injected anywhere
 * JUnit resolves parameters.
 *
 * Right after the test method, before any `@AfterEach`, the test fails if a call during
 * `@BeforeEach` or the test got no step: a call after the scenario's last step, or a call with no
 * scenario loaded in strict mode. An app that catches those 400s and carries on cannot pass that
 * way. [FakeLlmAllowUnservedCalls] turns the check off for a test that makes such a call on purpose.
 * A test that passed also fails if its scenario has steps no call used, such as a retry the app
 * never made; [FakeLlmAllowUnusedSteps] turns that check off. An aborted test (a failed assumption)
 * is not checked.
 *
 * Test classes may run in parallel; each has its own server and port. The tests of one class share
 * one engine, so they must not run concurrently: a second concurrent test fails at once with an
 * explanation. With JUnit's parallel execution, set
 * `junit.jupiter.execution.parallel.mode.default=same_thread` and
 * `junit.jupiter.execution.parallel.mode.classes.default=concurrent`.
 */
public class FakeLlmExtension
    @JvmOverloads
    constructor(
        private val scenarioDirectory: Path? = null,
        private val lenient: Boolean = false,
    ) : BeforeAllCallback,
        BeforeEachCallback,
        AfterTestExecutionCallback,
        AfterEachCallback,
        ParameterResolver {
        override fun beforeAll(context: ExtensionContext) {
            serverFor(context)
        }

        override fun beforeEach(context: ExtensionContext) {
            // Keys include this invocation's unique ID, so no other test (and no parent store) can
            // hold them. If the extension is registered twice, the second instance finds the flag
            // and leaves this invocation alone.
            val store = context.getStore(NAMESPACE)
            val id = context.uniqueId
            if (store.get(HANDLED + id) != null) return
            store.put(HANDLED + id, true)

            val holder = serverFor(context)
            val running = holder.inFlight.incrementAndGet()
            store.put(ENTERED + id, true)
            check(running == 1) { CONCURRENT_TESTS }

            val engine = holder.server.engine
            val scenario = scenarioFor(context)
            if (scenario != null) engine.load(scenario) else engine.unload()
            // Only a test that got this far is checked. JUnit already skips afterTestExecution when a
            // beforeEach fails (the guard above, or a scenario that does not load); the marker keeps
            // such a test from reading another test's log even if that ever changes, and lets only
            // one of two registrations run the check.
            store.put(CHECK + id, true)
        }

        override fun afterTestExecution(context: ExtensionContext) {
            // remove is atomic, so with two registrations only one instance checks. The log is read
            // here, while this test still holds the in-flight guard that afterEach releases.
            if (context.getStore(NAMESPACE).remove(CHECK + context.uniqueId) == null) return
            val failure = context.executionException.orElse(null)
            if (failure.isAbort()) return
            val engine = serverFor(context).server.engine
            if (!allowsUnservedCalls(context)) {
                val unserved = engine.calls().filter { it.outcome in UNSERVED }
                if (unserved.isNotEmpty()) throw AssertionError(unservedMessage(engine.currentScenarioName(), unserved))
            }
            // A test that already failed often stopped before its last calls, so its unused steps add nothing.
            if (failure == null && !allowsUnusedSteps(context)) {
                val unused = engine.unusedSteps
                if (unused.isNotEmpty()) throw AssertionError(unusedMessage(engine.currentScenarioName(), unused))
            }
        }

        override fun afterEach(context: ExtensionContext) {
            // Only an invocation whose beforeEach counted itself in is counted out, whichever
            // registered instance runs this; remove is atomic, so that happens once.
            if (context.getStore(NAMESPACE).remove(ENTERED + context.uniqueId) != null) {
                serverFor(context).inFlight.decrementAndGet()
            }
        }

        override fun supportsParameter(
            parameterContext: ParameterContext,
            extensionContext: ExtensionContext,
        ): Boolean = parameterContext.parameter.type in INJECTABLE

        override fun resolveParameter(
            parameterContext: ParameterContext,
            extensionContext: ExtensionContext,
        ): Any {
            val server = serverFor(extensionContext).server
            return if (parameterContext.parameter.type == ScenarioEngine::class.java) server.engine else server
        }

        /** The class's server, started on first use, whichever callback or context asks first. */
        private fun serverFor(context: ExtensionContext): ServerHolder =
            classContext(context)
                .getStore(NAMESPACE)
                // getOrComputeIfAbsent is deprecated from JUnit 6.0 in favour of computeIfAbsent,
                // which 5.10 (the oldest supported version) does not have.
                .getOrComputeIfAbsent(SERVER, { startServer(context) }, ServerHolder::class.java)

        private fun startServer(context: ExtensionContext): ServerHolder {
            val annotation = findOnClassOrEnclosing(context.requiredTestClass) { it.find<FakeLlmTest>() }
            val directory =
                if (annotation != null) annotation.scenarioDirectory.takeIf { it.isNotEmpty() }?.let { Path.of(it) } else scenarioDirectory
            val engine = ScenarioEngine(lenient = annotation?.lenient ?: lenient, scenarioDirectory = directory)
            // controlToken = null: a FAKE_LLM_CONTROL_TOKEN in the developer's environment must not gate the test server.
            return ServerHolder(startFakeLlmServer(port = 0, engine = engine, controlToken = null))
        }
    }

/**
 * The class-level context: the nearest context without a test method. The server lives in its
 * store, so it is shared by the class's tests (and its `@Nested` classes, whose stores fall back to
 * it) whichever context a callback receives.
 */
private fun classContext(context: ExtensionContext): ExtensionContext {
    var current = context
    while (current.testMethod.isPresent) {
        current = current.parent.orElseThrow()
    }
    return current
}

/**
 * True for what Jupiter reports as an abort: opentest4j's [TestAbortedException], or JUnit 4's
 * `org.junit.internal.AssumptionViolatedException` and its subclasses, matched by name because
 * JUnit 4 is not a dependency.
 */
private fun Throwable?.isAbort(): Boolean =
    this is TestAbortedException ||
        generateSequence<Class<*>>(this?.javaClass) { it.superclass }.any { it.name == JUNIT4_ASSUMPTION }

private const val JUNIT4_ASSUMPTION = "org.junit.internal.AssumptionViolatedException"

/** [FakeLlmAllowUnservedCalls] on the method, then the class, then each enclosing class. */
private fun allowsUnservedCalls(context: ExtensionContext): Boolean =
    context.testMethod.orElse(null)?.find<FakeLlmAllowUnservedCalls>() != null ||
        findOnClassOrEnclosing(context.requiredTestClass) { it.find<FakeLlmAllowUnservedCalls>() } != null

/** [FakeLlmAllowUnusedSteps] on the method, then the class, then each enclosing class. */
private fun allowsUnusedSteps(context: ExtensionContext): Boolean =
    context.testMethod.orElse(null)?.find<FakeLlmAllowUnusedSteps>() != null ||
        findOnClassOrEnclosing(context.requiredTestClass) { it.find<FakeLlmAllowUnusedSteps>() } != null

private fun unusedMessage(
    scenario: String?,
    steps: List<Int>,
): String {
    val which = if (steps.size == 1) "step ${steps.single()}" else "steps ${steps.first()}-${steps.last()}"
    return "${steps.size} step(s) of fake-llm scenario '$scenario' got no call: $which. " +
        "The app made fewer calls than the scenario scripts. Remove the steps the test does not need, " +
        "or annotate a test that leaves steps unused on purpose with @FakeLlmAllowUnusedSteps."
}

private fun unservedMessage(
    scenario: String?,
    calls: List<CallRecord>,
): String =
    buildString {
        val loaded = if (scenario != null) "scenario '$scenario'" else "no scenario loaded"
        append("${calls.size} fake-llm call(s) got no step ($loaded):")
        calls.forEach { append("\n  call ${it.seq}: ${it.vendor} model '${it.model}', ${it.outcome}") }
        append("\nAdd the steps the test needs, or annotate a test that makes such calls on purpose with @FakeLlmAllowUnservedCalls.")
    }

/** The scenario for this test: the method's [FakeLlmScenario], then the class's, then each enclosing class's. */
private fun scenarioFor(context: ExtensionContext): String? =
    context.testMethod
        .orElse(null)
        ?.find<FakeLlmScenario>()
        ?.value
        ?: findOnClassOrEnclosing(context.requiredTestClass) { it.find<FakeLlmScenario>() }?.value

/**
 * Finds the annotation the way JUnit finds `@ExtendWith`: directly present, inside a composed
 * annotation, or (for classes) on an implemented interface or, if `@Inherited`, a superclass.
 */
private inline fun <reified A : Annotation> AnnotatedElement.find(): A? = AnnotationSupport.findAnnotation(this, A::class.java).orElse(null)

private fun <T> findOnClassOrEnclosing(
    testClass: Class<*>,
    find: (Class<*>) -> T?,
): T? = generateSequence(testClass) { it.enclosingClass }.firstNotNullOfOrNull(find)

/**
 * The server in the class store. JUnit 5.10 to 5.12 close store values that are
 * [ExtensionContext.Store.CloseableResource]; 5.13 and later close [AutoCloseable] ones (and warn
 * about values that are only the former). Implementing both closes it on every version, once.
 */
private class ServerHolder(
    val server: FakeLlmServer,
) : ExtensionContext.Store.CloseableResource,
    AutoCloseable {
    val inFlight = AtomicInteger()
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) server.close()
    }
}
