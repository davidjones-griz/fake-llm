package com.davidjonesgriz.fakellm.junit

import com.davidjonesgriz.fakellm.junit.fixtures.ClassLevelTagSelectionFixture
import com.davidjonesgriz.fakellm.junit.fixtures.FIXTURES_PARAMETER
import com.davidjonesgriz.fakellm.junit.fixtures.RegisteredTagSelectionFixture
import com.davidjonesgriz.fakellm.junit.fixtures.Sink
import com.davidjonesgriz.fakellm.junit.fixtures.TagSelectionFixture
import com.davidjonesgriz.fakellm.junit.fixtures.UnknownScenarioTagSelectionFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * `fakellm.tags.include` and `fakellm.tags.exclude`: a test whose scenario's top-level tags don't
 * match the selection is skipped with a reason; every other test runs as before.
 */
class TagSelectionTest {
    @BeforeEach
    fun clearSink() = Sink.clear()

    private val all = listOf("malformed", "malformedSlow", "smoke", "uppercase", "untagged", "noScenario")

    /**
     * Runs [classes] with the selection as configuration parameters. Implicit configuration
     * parameters (JVM system properties, `junit-platform.properties`) are on, as in a real run;
     * the EngineTestKit leaves them off by default.
     */
    private fun run(
        vararg classes: Class<*>,
        include: String? = null,
        exclude: String? = null,
    ): EngineExecutionResults =
        EngineTestKit
            .engine("junit-jupiter")
            .enableImplicitConfigurationParameters(true)
            .configurationParameter(FIXTURES_PARAMETER, "true")
            .configurationParameters(
                listOfNotNull(include?.let { INCLUDE to it }, exclude?.let { EXCLUDE to it }).toMap(),
            ).selectors(*classes.map { selectClass(it) }.toTypedArray())
            .execute()

    /** The skipped tests, by method name, with their reasons. */
    private fun EngineExecutionResults.skipped(): Map<String, String> =
        testEvents()
            .skipped()
            .list()
            .associate { it.testDescriptor.displayName.substringBefore('(') to it.getRequiredPayload(String::class.java) }

    private fun EngineExecutionResults.assertNoFailures() {
        assertEquals(
            emptyList(),
            testEvents().failed().list().map { it.testDescriptor.displayName },
            "failed tests",
        )
    }

    private fun ran(): List<String> = Sink.get("tags:ran").map { it as String }

    @Test
    fun `with no selection every test runs`() {
        val results = run(TagSelectionFixture::class.java)
        results.assertNoFailures()
        assertEquals(emptyMap(), results.skipped())
        assertEquals(all.sorted(), ran().sorted())
    }

    @Test
    fun `include runs only the tests whose scenario has one of the tags, and skips untagged scenarios`() {
        val results = run(TagSelectionFixture::class.java, include = "malformed")
        results.assertNoFailures()
        assertEquals(listOf("malformed", "malformedSlow", "noScenario"), ran().sorted())
        val skipped = results.skipped()
        assertEquals(setOf("smoke", "uppercase", "untagged"), skipped.keys)
        assertEquals(
            "fake-llm scenario 'tagged-smoke' has tags [smoke], none of them in fakellm.tags.include [malformed]",
            skipped["smoke"],
        )
        assertEquals(
            "fake-llm scenario 'tagged-uppercase' has tags [Malformed], none of them in fakellm.tags.include [malformed]",
            skipped["uppercase"],
            "matching is case-sensitive",
        )
        assertEquals("fake-llm scenario 'untagged' has no tags, and fakellm.tags.include is [malformed]", skipped["untagged"])
        assertEquals(3, Sink.get("tags:beforeEach").size, "a skipped test's @BeforeEach does not run")
    }

    @Test
    fun `include takes any of several tags`() {
        run(TagSelectionFixture::class.java, include = "smoke,Malformed").assertNoFailures()
        assertEquals(listOf("noScenario", "smoke", "uppercase"), ran().sorted())
    }

    @Test
    fun `exclude skips the tests whose scenario has one of the tags, and runs untagged scenarios`() {
        val results = run(TagSelectionFixture::class.java, exclude = "slow")
        results.assertNoFailures()
        assertEquals(all.filter { it != "malformedSlow" }.sorted(), ran().sorted())
        assertEquals(
            mapOf(
                "malformedSlow" to "fake-llm scenario 'tagged-malformed-slow' has tag 'slow', which fakellm.tags.exclude [slow] excludes",
            ),
            results.skipped(),
        )
    }

    @Test
    fun `exclude wins over include`() {
        val results = run(TagSelectionFixture::class.java, include = "malformed", exclude = "slow")
        results.assertNoFailures()
        assertEquals(listOf("malformed", "noScenario"), ran().sorted())
        assertEquals(
            "fake-llm scenario 'tagged-malformed-slow' has tag 'slow', which fakellm.tags.exclude [slow] excludes",
            results.skipped()["malformedSlow"],
        )
    }

    @Test
    fun `blank and empty entries are ignored, and a blank selection selects nothing`() {
        run(TagSelectionFixture::class.java, include = " , malformed ,, ", exclude = " ,").assertNoFailures()
        assertEquals(listOf("malformed", "malformedSlow", "noScenario"), ran().sorted())
        Sink.clear()
        val blank = run(TagSelectionFixture::class.java, include = " ", exclude = ",,")
        blank.assertNoFailures()
        assertEquals(emptyMap(), blank.skipped())
        assertEquals(all.sorted(), ran().sorted())
    }

    @Test
    fun `the selection can come from JVM system properties`() {
        System.setProperty(INCLUDE, "smoke")
        try {
            run(TagSelectionFixture::class.java).assertNoFailures()
        } finally {
            System.clearProperty(INCLUDE)
        }
        assertEquals(listOf("noScenario", "smoke"), ran().sorted())
    }

    @Test
    fun `a class-level scenario selects the class's tests, and a nested test's own scenario selects it`() {
        val results = run(ClassLevelTagSelectionFixture::class.java, include = "malformed")
        results.assertNoFailures()
        assertEquals(listOf("innerMalformed"), ran())
        assertEquals(setOf("outer", "innerInherited"), results.skipped().keys)
    }

    @Test
    fun `the selection applies to an extension registered on an instance field`() {
        val results = run(RegisteredTagSelectionFixture::class.java, include = "malformed")
        results.assertNoFailures()
        assertEquals(listOf("registeredMalformed"), ran())
        assertEquals(setOf("registeredUntagged"), results.skipped().keys)
    }

    @Test
    fun `a scenario that does not load still fails the test with the loader's message`() {
        val results = run(UnknownScenarioTagSelectionFixture::class.java, include = "malformed")
        val failure =
            results
                .testEvents()
                .failed()
                .list()
                .single()
        assertContains(
            failure
                .getRequiredPayload(org.junit.platform.engine.TestExecutionResult::class.java)
                .throwable
                .get()
                .message
                .orEmpty(),
            "unknown scenario 'no-such-scenario-for-tags'",
        )
        assertEquals(emptyList(), ran())
    }

    private companion object {
        const val INCLUDE = "fakellm.tags.include"
        const val EXCLUDE = "fakellm.tags.exclude"
    }
}
