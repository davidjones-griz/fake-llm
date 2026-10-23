package com.davidjonesgriz.fakellm.junit

import com.davidjonesgriz.fakellm.FakeLlmServer
import com.davidjonesgriz.fakellm.ScenarioEngine
import com.davidjonesgriz.fakellm.startFakeLlmServer
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.platform.commons.support.AnnotationSupport
import java.lang.reflect.AnnotatedElement
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(FakeLlmExtension::class.java)
private const val SERVER = "server"
private const val HANDLED = "handled:"
private const val ENTERED = "entered:"
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
 * also clears the call log and counters; a test with none runs after [ScenarioEngine.unload].
 * `FakeLlmServer` and `ScenarioEngine` parameters are injected anywhere JUnit resolves parameters.
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
