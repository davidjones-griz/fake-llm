package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class InfoRoutesTest {
    private val anthropicModel =
        """{"type":"model","id":"%s","display_name":"%s","created_at":"1970-01-01T00:00:00Z","lifecycle":"active"}"""
    private val openAiModel = """{"id":"%s","object":"model","created":0,"owned_by":"fake-llm"}"""
    private val geminiModel =
        """{"name":"models/%s","version":"fake-llm","displayName":"%s",""" +
            """"supportedGenerationMethods":["generateContent","streamGenerateContent","embedContent","batchEmbedContents"]}"""

    /** Each route's expected body: the three lists, then a lookup of a model id on each vendor. */
    private val bodies =
        listOf(
            "/anthropic/v1/models" to """{"data":[${anthropicModel.format("fake-llm", "fake-llm")}],""" +
                """"has_more":false,"first_id":"fake-llm","last_id":"fake-llm"}""",
            "/openai/v1/models" to """{"object":"list","data":[${openAiModel.format("fake-llm")}]}""",
            "/gemini/v1beta/models" to """{"models":[${geminiModel.format("fake-llm", "fake-llm")}]}""",
            "/anthropic/v1/models/example-model" to anthropicModel.format("example-model", "example-model"),
            "/openai/v1/models/example-gpt" to openAiModel.format("example-gpt"),
            "/gemini/v1/models/example-gemini" to geminiModel.format("example-gemini", "example-gemini"),
        )

    private val countPath = "/anthropic/v1/messages/count_tokens"

    private fun assertNotLogged(client: TestClient) {
        assertEquals("[]", client.get("/control/calls").body())
        assertEquals("[]", client.get("/control/exchanges").body())
    }

    @Test
    fun `count_tokens answers 100 input tokens for any body the Messages route accepts, and takes no step`() {
        withServer(ScenarioEngine().also { it.loadInline(Scenario(name = "one", steps = listOf(Step(text = "only")))) }) { client, engine ->
            val response = client.post(countPath, anthropicBody("a long prompt ".repeat(50)))
            assertEquals(200, response.statusCode())
            assertEquals(listOf("application/json"), response.headers().allValues("content-type"))
            assertEquals("""{"input_tokens":100}""", response.body())
            assertNotLogged(client)
            assertEquals(listOf(1), engine.unusedSteps)
        }
    }

    @Test
    fun `a count_tokens body the Messages parser rejects is the exception 400`() {
        withServer { client, _ ->
            listOf("not json", "[]", """{"model":"example-model"}""").forEach { body ->
                val response = client.post(countPath, body)
                assertEquals(400, response.statusCode(), body)
                val error =
                    Json
                        .parseToJsonElement(response.body())
                        .jsonObject["fake_llm_error"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                assertEquals("exception", error, body)
            }
            assertNotLogged(client)
        }
    }

    @Test
    fun `the model lists hold one model, fake-llm, and a lookup echoes the requested id`() {
        withServer { client, _ ->
            bodies.forEach { (path, body) ->
                val response = client.get(path)
                assertEquals(200, response.statusCode(), path)
                assertEquals(listOf("application/json"), response.headers().allValues("content-type"), path)
                assertEquals(body, response.body(), path)
            }
            assertNotLogged(client)
        }
    }

    @Test
    fun `the answers are the same with no scenario, with one loaded, and in lenient mode`() {
        val strict = ScenarioEngine()
        val loaded = ScenarioEngine().also { it.loadInline(Scenario(name = "one", steps = listOf(Step(text = "only")))) }
        val lenient = ScenarioEngine(lenient = true)
        listOf(strict, loaded, lenient).forEach { engine ->
            withServer(engine) { client, _ ->
                bodies.forEach { (path, body) -> assertEquals(body, client.get(path).body(), path) }
                assertEquals("""{"input_tokens":100}""", client.post(countPath, anthropicBody("hi")).body())
                assertNotLogged(client)
            }
        }
    }

    @Test
    fun `another method on these paths, a trailing slash or a Gemini method path is 404`() {
        withServer { client, _ ->
            assertEquals(404, client.get(countPath).statusCode())
            listOf("/anthropic/v1/models", "/openai/v1/models", "/gemini/v1beta/models", "/openai/v1/models/example-gpt").forEach {
                assertEquals(404, client.post(it, "{}").statusCode(), it)
            }
            listOf("/openai/v1/models/", "/anthropic/v1/models/a/b", "/gemini/v1beta/models/example-gemini:generateContent").forEach {
                assertEquals(404, client.get(it).statusCode(), it)
            }
            assertNotLogged(client)
        }
    }

    @Test
    fun `a lookup echoes an encoded id decoded, including one with a slash`() {
        withServer { client, _ ->
            listOf(
                "/openai/v1/models/meta-llama%2FLlama-3.1-8B" to openAiModel.format("meta-llama/Llama-3.1-8B"),
                "/openai/v1/models/ft:gpt-4o:org::x1" to openAiModel.format("ft:gpt-4o:org::x1"),
                "/anthropic/v1/models/a%20b+c" to anthropicModel.format("a b+c", "a b+c"),
                "/gemini/v1beta/models/tuned%2Fx" to geminiModel.format("tuned/x", "tuned/x"),
            ).forEach { (path, body) -> assertEquals(body, client.get(path).body(), path) }
            assertEquals(404, client.get("/gemini/v1beta/models/m%3AgenerateContent").statusCode())
        }
    }

    @Test
    fun `the routes need no control token when one is set`() {
        withServer(controlToken = "secret") { client, _ ->
            assertEquals(200, client.get("/openai/v1/models").statusCode())
            assertEquals(200, client.post(countPath, anthropicBody("hi")).statusCode())
        }
    }
}
