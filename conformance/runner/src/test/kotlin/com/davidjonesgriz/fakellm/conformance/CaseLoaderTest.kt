package com.davidjonesgriz.fakellm.conformance

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaseLoaderTest {
    private val loader = CaseLoader(Path.of(System.getProperty("fakellm.schema")))

    private fun withCases(
        files: Map<String, String>,
        block: (Path) -> Unit,
    ) {
        val dir = Files.createTempDirectory("cases")
        try {
            files.forEach { (path, text) -> dir.resolve(path).also { it.parent.createDirectories() }.writeText(text) }
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun case(
        name: String,
        extra: String = "",
        scenario: String = "\"hello\"",
    ) = """{"name":"$name","description":"d","scenario":$scenario,$extra
        "exchanges":[{"request":{"method":"GET","path":"/health"},"response":{"status":200}}]}"""

    private fun problems(files: Map<String, String>): List<String> {
        var problems = emptyList<String>()
        withCases(files) { dir -> problems = assertFailsWith<InvalidCasesException> { loader.load(dir) }.problems }
        return problems
    }

    @Test
    fun `valid cases load in path order, with their settings and exchanges`() {
        withCases(
            mapOf(
                "b/second.json" to case("second", """"server":{"lenient":true,"controlToken":"t"},"""),
                "a/first.json" to case("first", """"server":{"fresh":true},""", scenario = "null"),
            ),
        ) { dir ->
            val cases = loader.load(dir)
            assertEquals(listOf("a/first.json", "b/second.json"), cases.map { it.relativePath })
            assertEquals(ServerSettings(fresh = true), cases[0].server)
            assertEquals(JsonNull, cases[0].scenario)
            assertEquals(ServerSettings(lenient = true, controlToken = "t"), cases[1].server)
            assertEquals(JsonPrimitive("hello"), cases[1].scenario)
            assertEquals(CaseRequest("GET", "/health"), cases[1].exchanges.single().request)
        }
    }

    @Test
    fun `a malformed case is rejected by the schema, naming the file`() {
        val malformed =
            mapOf(
                "no name" to """{"description":"d","scenario":"hello","exchanges":[]}""",
                "no scenario" to
                    """{"name":"x","description":"d","exchanges":[{"request":{"method":"GET","path":"/"},"response":{"status":200}}]}""",
                "a null scenario without fresh" to case("x", scenario = "null"),
                "both body and rawBody" to
                    """{"name":"x","description":"d","scenario":"hello","exchanges":[{"request":{"method":"POST","path":"/","body":{},"rawBody":"{"},"response":{"status":200}}]}""",
                "an unknown matcher" to
                    """{"name":"x","description":"d","scenario":"hello","exchanges":[{"request":{"method":"GET","path":"/"},"response":{"status":200,"body":{"t":{"${'$'}type":"boolean"}}}}]}""",
                "a dollar key in an expected object" to
                    """{"name":"x","description":"d","scenario":"hello","exchanges":[{"request":{"method":"GET","path":"/"},"response":{"status":200,"body":{"${'$'}ref":"x"}}}]}""",
                "an unknown field" to case("x", """"extra":1,"""),
                "a restricted request header" to
                    """{"name":"x","description":"d","scenario":"hello","exchanges":[{"request":{"method":"GET","path":"/","headers":{"Host":"h"}},"response":{"status":200}}]}""",
                "two expected body forms" to
                    """{"name":"x","description":"d","scenario":"hello","exchanges":[{"request":{"method":"GET","path":"/"},"response":{"status":200,"body":{},"rawBody":""}}]}""",
            )
        malformed.forEach { (what, text) ->
            val found = problems(mapOf("area/x.json" to text))
            assertTrue(found.isNotEmpty() && found.all { it.startsWith("area/x.json: ") }, "$what: $found")
        }
    }

    @Test
    fun `a name that differs from its file, or is used twice, is rejected`() {
        assertContains(problems(mapOf("a/other.json" to case("mismatch"))).single(), "name 'mismatch' must equal the file name")
        assertContains(
            problems(mapOf("a/same.json" to case("same"), "b/same.json" to case("same"))).single(),
            "case name 'same' is used by a/same.json, b/same.json",
        )
    }

    @Test
    fun `no case files at all is a failure, not a green run`() {
        assertContains(problems(emptyMap()).single(), "no case files")
        assertFailsWith<IllegalArgumentException> { loader.load(Path.of("no/such/dir")) }
    }

    @Test
    fun `the repository's case files are all valid`() {
        assertTrue(loader.load(Path.of(System.getProperty("fakellm.casesDir"))).isNotEmpty())
    }
}
