package com.davidjonesgriz.fakellm.junit

import org.junit.jupiter.api.extension.ExtendWith
import java.lang.annotation.Inherited

/**
 * Runs the test class with a fake-llm server: [FakeLlmExtension] starts one server for the class on
 * an ephemeral port, loads each test's [FakeLlmScenario], and injects the server's
 * `FakeLlmServer` and `ScenarioEngine` into constructors, `@BeforeAll`, `@BeforeEach` and test
 * methods. Subclasses inherit it, and `@Nested` classes share the outer class's server.
 *
 * [scenarioDirectory] is searched before the classpath when a scenario is loaded by name, as with
 * `ScenarioEngine(scenarioDirectory = ...)`; a relative path resolves against the working
 * directory, and an empty string means the classpath only. When [lenient] is true, a request no
 * rule matches gets the canned reply instead of HTTP 400.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@ExtendWith(FakeLlmExtension::class)
public annotation class FakeLlmTest(
    val scenarioDirectory: String = "",
    val lenient: Boolean = false,
)

/**
 * The scenario [FakeLlmExtension] loads before each test, by name. On a method it applies to that
 * test; on a class, to every test in it and in its `@Nested` classes and subclasses. The nearest
 * annotation wins: the method's, then the class's (its own, then its interfaces', then its
 * superclasses'), then each enclosing class's, the same way. It is also found inside a composed
 * annotation. A test with none runs with no scenario loaded.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
public annotation class FakeLlmScenario(
    val value: String,
)
