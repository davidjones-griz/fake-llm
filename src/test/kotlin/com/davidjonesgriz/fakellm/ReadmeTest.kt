package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The README stays an introduction; the reference lives on the documentation site (docs/). */
class ReadmeTest {
    private val readme = File("README.md").readLines()

    @Test
    fun `the README is at most the line limit`() {
        assertTrue(readme.size <= MAX_README_LINES, "README.md has ${readme.size} lines; move reference material to docs/")
    }

    @Test
    fun `the README has no scenario field table rows`() {
        val schema = Json.parseToJsonElement(File("schema/scenario.schema.json").readText()).jsonObject
        val objects =
            listOf(schema) +
                schema
                    .getValue("\$defs")
                    .jsonObject.values
                    .map { it.jsonObject }
        val names = objects.flatMap { it.getValue("properties").jsonObject.keys }.toSet() - "\$schema"
        val rows = readme.filter { line -> names.any { line.startsWith("| `$it` |") } }
        assertEquals(emptyList(), rows, "scenario field rows belong in docs/scenarios.md")
    }

    @Test
    fun `the README keeps the headings that workflow comments cite`() {
        val kept = listOf("## Run in CI", "### Quality gates", "### Dependency updates", "### Releasing", "### Public API")
        assertEquals(emptyList(), kept.filter { it !in readme })
    }

    private companion object {
        /** The README introduces fake-llm; reference material lives on the site (docs/). */
        const val MAX_README_LINES = 400
    }
}
