package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Vertex AI paths for Gemini: the regional `projects/{p}/locations/{l}/publishers/google/models/{m}`
 * form and the express-mode `publishers/google/models/{m}` form, both served by the Gemini handling.
 */
class VertexRoutingTest {
    private val body = """{"contents":[{"role":"user","parts":[{"text":"Hi"}]}]}"""

    private fun engineWith(vararg steps: Step) =
        ScenarioEngine().also {
            it.loadInline(Scenario(metadata = ScenarioMetadata(name = "vertex"), steps = steps.toList()))
        }

    private fun modelVersion(json: String): String =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("modelVersion")
            .jsonPrimitive.content

    private fun replyText(json: String): String =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("candidates")
            .jsonArray[0]
            .jsonObject
            .getValue("content")
            .jsonObject
            .getValue("parts")
            .jsonArray[0]
            .jsonObject
            .getValue("text")
            .jsonPrimitive.content

    @Test
    fun `generateContent on both Vertex shapes is a Gemini call with the model from the path`() {
        listOf(
            "/gemini/v1/projects/my-project/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent",
            "/gemini/v1beta1/projects/my-project/locations/global/publishers/google/models/gemini-2.5-flash:generateContent",
            "/gemini/v1/publishers/google/models/gemini-2.5-flash:generateContent",
            "/gemini/v1beta1/publishers/google/models/gemini-2.5-flash:generateContent",
        ).forEach { path ->
            withServer(engineWith(Step(text = "Hello from Vertex."))) { client, engine ->
                val resp = client.post(path, body)
                assertEquals(200, resp.statusCode(), "$path: ${resp.body()}")
                assertEquals("Hello from Vertex.", replyText(resp.body()), path)
                assertEquals("gemini-2.5-flash", modelVersion(resp.body()), path)
                val call = engine.calls().single()
                assertEquals("gemini", call.vendor, path)
                assertEquals("gemini-2.5-flash", call.model, path)
            }
        }
    }

    @Test
    fun `a Vertex reply is the same body the Developer API path gets`() {
        fun reply(path: String): String {
            var out = ""
            withServer(engineWith(Step(text = "Same either way."))) { client, _ -> out = client.post(path, body).body() }
            return out
        }
        val developer = reply("/gemini/v1beta/models/m:generateContent")
        assertEquals(developer, reply("/gemini/v1/projects/p/locations/l/publishers/google/models/m:generateContent"))
        assertEquals(developer, reply("/gemini/v1/publishers/google/models/m:generateContent"))
    }

    @Test
    fun `streamGenerateContent on both Vertex shapes streams with alt=sse, and without it is the invalid-request 400`() {
        listOf(
            "/gemini/v1/projects/p/locations/us-central1/publishers/google/models/m:streamGenerateContent",
            "/gemini/v1/publishers/google/models/m:streamGenerateContent",
        ).forEach { path ->
            withServer(engineWith(Step(text = "Streamed.", streamChunkChars = 4))) { client, engine ->
                val missing = client.post(path, body)
                assertEquals(400, missing.statusCode(), path)
                assertContains(missing.body(), "\"fake_llm_error\":\"invalid-request\"")
                assertEquals(emptyList(), engine.calls(), "$path: without alt=sse is not a call")

                val resp = client.post("$path?alt=sse", body)
                assertEquals(200, resp.statusCode(), "$path: ${resp.body()}")
                assertTrue(
                    resp
                        .headers()
                        .firstValue("content-type")
                        .get()
                        .startsWith("text/event-stream"),
                    path,
                )
                val frames =
                    resp
                        .body()
                        .lines()
                        .filter { it.startsWith("data: ") }
                        .map { it.removePrefix("data: ") }
                assertEquals("Streamed.", frames.joinToString("") { replyText(it) }, path)
                assertTrue(frames.all { modelVersion(it) == "m" }, path)
                assertEquals(listOf("gemini"), engine.calls().map { it.vendor }, path)
            }
        }
    }

    @Test
    fun `the model is the last segment, even when a project or location is named models`() {
        withServer(engineWith(Step(text = "ok"))) { client, _ ->
            val resp = client.post("/gemini/v1/projects/models/locations/models/publishers/google/models/m:generateContent", body)
            assertEquals(200, resp.statusCode(), resp.body())
            assertEquals("m", modelVersion(resp.body()))
        }
    }

    @Test
    fun `the Vertex routes match their documented paths exactly`() {
        withServer { client, _ ->
            val regional = "/gemini/v1/projects/p/locations/l/publishers/google/models/m"
            val express = "/gemini/v1/publishers/google/models/m"
            assertEquals(400, client.post("$regional:generateContent", body).statusCode(), "the documented path, no scenario")
            assertEquals(400, client.post("$express:generateContent", body).statusCode(), "the documented path, no scenario")
            listOf(
                "/gemini/v1/projects/p/locations/l/publishers/anthropic/models/m:generateContent",
                "/gemini/v1/publishers/meta/models/m:generateContent",
                "/gemini/v1/projects/p/publishers/google/models/m:generateContent",
                "/gemini/v1/locations/l/publishers/google/models/m:generateContent",
                "/gemini/v1/projects/p/locations/l/models/m:generateContent",
                "/gemini/v1/projects/p/locations/l/endpoints/e:generateContent",
                "/gemini/projects/p/locations/l/publishers/google/models/m:generateContent",
                "/gemini/publishers/google/models/m:generateContent",
                "$regional/extra:generateContent",
                "$express/extra:generateContent",
                "$regional:generateContentXYZ",
                "$express:streamGenerateContentXYZ?alt=sse",
                "/v1/projects/p/locations/l/publishers/google/models/m:generateContent",
                "/v1/publishers/google/models/m:generateContent",
            ).forEach { path -> assertEquals(404, client.post(path, body).statusCode(), path) }
            assertEquals(404, client.get("$regional:generateContent").statusCode())
            assertEquals(404, client.get("$express:generateContent").statusCode())
        }
    }

    @Test
    fun `Vertex embeddings, token counting and model lookup are not routed`() {
        withServer { client, _ ->
            val embed = """{"content":{"parts":[{"text":"x"}]}}"""
            listOf(
                "/gemini/v1/projects/p/locations/l/publishers/google/models/m:embedContent",
                "/gemini/v1/publishers/google/models/m:embedContent",
                "/gemini/v1/projects/p/locations/l/publishers/google/models/m:predict",
                "/gemini/v1/projects/p/locations/l/publishers/google/models/m:countTokens",
            ).forEach { path ->
                val resp = client.post(path, embed)
                assertEquals(404, resp.statusCode(), path)
                assertContains(resp.body(), "\"fake_llm_error\":\"no-route\"")
            }
            assertEquals(404, client.get("/gemini/v1/projects/p/locations/l/publishers/google/models/m").statusCode())
            assertEquals(404, client.get("/gemini/v1/publishers/google/models").statusCode())
        }
    }
}
