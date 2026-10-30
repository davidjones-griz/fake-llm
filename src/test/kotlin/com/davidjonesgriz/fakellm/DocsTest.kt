package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The documentation site's pages cover the scenario model, the control API and the recipes' scenarios. */
class DocsTest {
    private val scenarioPage = File("docs/scenarios.md").readText()

    /** The text from `### [heading]` to the next `###` or `##` heading. */
    private fun section(
        page: String,
        heading: String,
    ): String {
        val start = page.indexOf("\n### $heading\n")
        assertTrue(start >= 0, "no '### $heading' heading")
        val next = Regex("\n##+ ").find(page, start + 1)?.range?.first ?: page.length
        return page.substring(start, next)
    }

    /** An image tag, or a `"version"` field in an example response such as `GET /health`'s. */
    private val versionPattern = Regex("""ghcr\.io/davidjones-griz/fake-llm:([0-9][0-9.]*[0-9])|"version":"([^"]*)"""")

    private fun fields(obj: JsonObject): Set<String> = obj.getValue("properties").jsonObject.keys - "\$schema"

    @Test
    fun `every scenario field has a row in its section of the scenario reference`() {
        val schema = Json.parseToJsonElement(File("schema/scenario.schema.json").readText()).jsonObject
        val objects = mapOf("Scenario" to fields(schema)) + schema.getValue("\$defs").jsonObject.mapValues { fields(it.value.jsonObject) }
        val missing =
            objects.flatMap { (name, names) ->
                val rows = section(scenarioPage, "$name fields")
                names.filter { "\n| `$it` |" !in rows }.map { "$name.$it" }
            }
        assertEquals(emptyList(), missing, "fields with no row in docs/scenarios.md")
    }

    @Test
    fun `every health, openapi and control operation has a heading in the control API page`() {
        val page = File("docs/control-api.md").readText()
        val paths = YAMLMapper().readTree(File("openapi/fake-llm.yaml")).path("paths")
        val operations =
            paths.propertyNames().filter { it == "/health" || it == "/openapi" || it.startsWith("/control/") }.flatMap { path ->
                paths
                    .path(path)
                    .propertyNames()
                    .filter { it in setOf("get", "post", "put", "delete") }
                    .map { "${it.uppercase()} $path" }
            }
        assertTrue(operations.size >= 7, "found only $operations")
        assertEquals(emptyList(), operations.filter { "\n### `$it`\n" !in page })
    }

    @Test
    fun `every recipe embeds a bundled scenario that the test suite loads`() {
        val tested = File("src/test/kotlin/com/davidjonesgriz/fakellm/BundledScenariosTest.kt").readText()
        val recipes = File("docs/recipes").listFiles { f -> f.extension == "md" && f.name != "index.md" }.orEmpty()
        assertEquals(5, recipes.size, "recipes: ${recipes.map { it.name }}")
        val embed = Regex("""--8<-- "(src/main/resources/com/davidjonesgriz/fakellm/scenarios/([a-z0-9-]+)\.json)"""")
        recipes.forEach { page ->
            val found = embed.findAll(page.readText()).toList()
            assertTrue(found.isNotEmpty(), "${page.name} embeds no bundled scenario")
            found.forEach { m ->
                val (path, name) = m.destructured
                assertTrue(File(path).isFile, "${page.name}: $path does not exist")
                assertTrue("client.load(\"$name\")" in tested, "${page.name}: BundledScenariosTest does not load $name")
            }
        }
    }

    @Test
    fun `the image tags and versions in the docs are this version`() {
        val version = File("gradle.properties").readLines().first { it.startsWith("version=") }.removePrefix("version=")
        val found =
            File("docs")
                .walkTopDown()
                .filter { it.isFile }
                .flatMap { f -> versionPattern.findAll(f.readText()).map { f.path to (it.groups[1] ?: it.groups[2])!!.value } }
                .toList()
        assertTrue(found.any { it.first.endsWith("quickstart.sh") }, "the quickstart names no image tag")
        assertEquals(emptyList(), found.filter { it.second != version })
    }
}
