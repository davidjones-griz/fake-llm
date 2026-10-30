package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EmbeddingRoutesTest {
    private fun json(response: HttpResponse<String>): JsonObject = Json.parseToJsonElement(response.body()).jsonObject

    private fun calls(client: TestClient): JsonArray = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray

    private fun openAi(
        client: TestClient,
        body: String,
    ): HttpResponse<String> = client.post("/openai/v1/embeddings", body)

    /** The 400's `detail`, after checking it is the `exception` 400. */
    private fun rejected(response: HttpResponse<String>): String {
        assertEquals(400, response.statusCode(), response.body())
        val body = json(response)
        assertEquals("exception", body["fake_llm_error"]?.jsonPrimitive?.contentOrNull)
        return body["detail"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun floats(embedding: JsonObject): List<Float> = embedding["embedding"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }

    private fun decode(base64: String): List<Float> {
        val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN)
        return List(buffer.remaining() / Float.SIZE_BYTES) { buffer.getFloat() }
    }

    @Test
    fun `an OpenAI request gets the list body, with the model echoed and usage estimated`() {
        withServer { client, _ ->
            val response = openAi(client, """{"model":"m","input":["Hello","Hello world"],"dimensions":4,"user":"u","extra":1}""")
            assertEquals(200, response.statusCode(), response.body())
            assertEquals(listOf("application/json"), response.headers().allValues("content-type"))
            val body = json(response)
            assertEquals(setOf("object", "data", "model", "usage"), body.keys)
            assertEquals("list", body["object"]!!.jsonPrimitive.content)
            assertEquals("m", body["model"]!!.jsonPrimitive.content)
            val data = body["data"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(0, 1), data.map { it["index"]!!.jsonPrimitive.int })
            assertEquals(fakeEmbedding("Hello", 4).toList(), floats(data[0]))
            // Hello is 2 tokens, Hello world 3.
            assertEquals("""{"prompt_tokens":5,"total_tokens":5}""", body["usage"].toString())
        }
    }

    @Test
    fun `the float and base64 forms carry the same float32 values, and the default size is 1536`() {
        withServer { client, _ ->
            val float = floats(json(openAi(client, """{"input":"same text"}"""))["data"]!!.jsonArray[0].jsonObject)
            val base64 = json(openAi(client, """{"input":"same text","encoding_format":"base64"}"""))
            assertEquals(1536, float.size)
            assertEquals(
                float,
                decode(
                    base64["data"]!!
                        .jsonArray[0]
                        .jsonObject["embedding"]!!
                        .jsonPrimitive.content,
                ),
            )
            assertEquals("", base64["model"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `each JSON number is the float32's exact double, so a float64 reader gets the float32 value`() {
        withServer { client, _ ->
            val text = json(openAi(client, """{"input":"Hello","dimensions":4}"""))["data"]!!.jsonArray[0].jsonObject["embedding"]!!
            assertEquals("[-0.14939652383327484,-0.9198163151741028,0.3256172835826874,-0.15997493267059326]", text.toString())
        }
    }

    @Test
    fun `invalid OpenAI requests get the exception 400`() {
        withServer { client, _ ->
            for (body in listOf(
                "[]",
                "not json",
                """{"input":""}""",
                """{"input":[]}""",
                """{"input":["a",""]}""",
                """{"input":[[]]}""",
                """{"input":["a",[1]]}""",
                """{"input":[1.5]}""",
                """{"input":{}}""",
                """{"input":"a","dimensions":0}""",
                """{"input":"a","dimensions":8193}""",
                """{"input":"a","dimensions":"8"}""",
                """{"input":"a","dimensions":8.0}""",
                """{"input":"a","encoding_format":"hex"}""",
                """{"input":"a","encoding_format":5}""",
            )) {
                rejected(openAi(client, body))
            }
            assertContains(rejected(openAi(client, """{"input":["a",1]}""")), "must not mix")
            assertEquals("dimensions must be from 1 to 8192, got 0", rejected(openAi(client, """{"input":"a","dimensions":0}""")))
            assertEquals(JsonArray(emptyList()), calls(client))
            assertEquals(JsonArray(emptyList()), exchanges(client))
        }
    }

    @Test
    fun `OpenAI input count limit is 2048 inputs, and a long token array is one input`() {
        withServer { client, _ ->
            val inputs = { n: Int -> List(n) { "\"t$it\"" }.joinToString(",", "[", "]") }
            assertEquals(200, openAi(client, """{"input":${inputs(2048)},"dimensions":1}""").statusCode())
            assertContains(rejected(openAi(client, """{"input":${inputs(2049)},"dimensions":1}""")), "at most 2048")
            val tokens = List(3000) { it }.joinToString(",", "[", "]")
            val one = json(openAi(client, """{"input":$tokens,"dimensions":1}"""))
            assertEquals(1, one["data"]!!.jsonArray.size)
            assertEquals(3000, one["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.int)
            assertEquals(200, openAi(client, """{"input":"one","dimensions":8192}""").statusCode())
        }
    }

    @Test
    fun `OpenAI value cap is 3145728 values, checked before any vector is built`() {
        withServer { client, _ ->
            val inputs = { n: Int -> List(n) { "\"t$it\"" }.joinToString(",", "[", "]") }
            val atCap = openAi(client, """{"input":${inputs(384)},"dimensions":8192,"encoding_format":"base64"}""")
            assertEquals(200, atCap.statusCode())
            assertEquals(384, json(atCap)["data"]!!.jsonArray.size)
            val over = openAi(client, """{"input":${inputs(385)},"dimensions":8192,"encoding_format":"base64"}""")
            assertContains(rejected(over), "more than 3145728 values")
        }
    }

    @Test
    fun `embeddings are not calls, and answer the same with no scenario and in lenient mode`() {
        val body = """{"input":"Hello","dimensions":8}"""
        val expected = withServerBody(ScenarioEngine(), body)
        assertEquals(expected, withServerBody(ScenarioEngine(lenient = true), body))
        withServer { client, _ ->
            client.post("/control/scenario", """{"scenario":{"name":"one","steps":[{"text":"only"}]}}""")
            assertEquals(expected, openAi(client, body).body())
            assertEquals(JsonArray(emptyList()), calls(client))
            assertEquals(JsonArray(emptyList()), exchanges(client))
            val next = json(client.post("/anthropic/v1/messages", anthropicBody("hi")))
            assertEquals(
                "only",
                next["content"]!!
                    .jsonArray[0]
                    .jsonObject["text"]!!
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `a GET on an embeddings route is the 404`() {
        withServer { client, _ -> assertEquals(404, client.get("/openai/v1/embeddings").statusCode()) }
    }

    private fun withServerBody(
        engine: ScenarioEngine,
        body: String,
    ): String {
        var answer = ""
        withServer(engine) { client, _ -> answer = openAi(client, body).body() }
        return answer
    }
}
