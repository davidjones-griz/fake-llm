package dev.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiProviderDialectTest {
    private val helloText = "Hello. I can help with the weather, a simple recipe, or a short how-to."

    private fun openAiBody(prompt: String): String =
        """{"model":"example-gpt","messages":[{"role":"system","content":${JsonPrimitive(prompt)}},""" +
            """{"role":"user","content":"hello"}]}"""

    private fun geminiBody(prompt: String): String =
        """{"contents":[{"role":"user","parts":[{"text":${JsonPrimitive(prompt)}}]}],""" +
            """"generationConfig":{"maxOutputTokens":256}}"""

    private val geminiPath = "/gemini/v1/models/example-gemini:generateContent"

    private fun openAiText(body: String): String {
        val choice = Json.parseToJsonElement(body).jsonObject.getValue("choices").jsonArray.single().jsonObject
        return choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
    }

    private fun geminiText(body: String): String {
        val candidate = Json.parseToJsonElement(body).jsonObject.getValue("candidates").jsonArray.single().jsonObject
        val parts = candidate.getValue("content").jsonObject.getValue("parts").jsonArray
        return parts.single().jsonObject.getValue("text").jsonPrimitive.content
    }

    @Test
    fun `an openai chat completion renders the loaded scenario text`() {
        withServer { client, _ ->
            assertEquals(200, client.post("/control/scenario", """{"name":"hello"}""").statusCode())
            val resp = client.post("/openai/v1/chat/completions", openAiBody("a short how-to"))
            assertEquals(200, resp.statusCode())
            val root = Json.parseToJsonElement(resp.body()).jsonObject
            assertEquals("chat.completion", root.getValue("object").jsonPrimitive.content)
            val choice = root.getValue("choices").jsonArray.single().jsonObject
            assertEquals("stop", choice.getValue("finish_reason").jsonPrimitive.content)
            assertEquals("assistant", choice.getValue("message").jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals(helloText, openAiText(resp.body()))

            val call = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray.single().jsonObject
            assertEquals("openai", call.getValue("vendor").jsonPrimitive.content)
            assertEquals("example-gpt", call.getValue("model").jsonPrimitive.content)
            assertEquals("blocks", call.getValue("systemShape").jsonPrimitive.content)
            assertEquals(2, call.getValue("messageCount").jsonPrimitive.content.toInt())
            assertEquals("assistant", call.getValue("role").jsonPrimitive.content)
        }
    }

    @Test
    fun `a gemini generateContent call renders the loaded scenario text`() {
        withServer { client, _ ->
            assertEquals(200, client.post("/control/scenario", """{"name":"hello"}""").statusCode())
            val resp = client.post(geminiPath, geminiBody("a short how-to"))
            assertEquals(200, resp.statusCode())
            val root = Json.parseToJsonElement(resp.body()).jsonObject
            val candidate = root.getValue("candidates").jsonArray.single().jsonObject
            assertEquals("STOP", candidate.getValue("finishReason").jsonPrimitive.content)
            assertEquals(helloText, geminiText(resp.body()))

            val call = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray.single().jsonObject
            assertEquals("gemini", call.getValue("vendor").jsonPrimitive.content)
            assertEquals("example-gemini", call.getValue("model").jsonPrimitive.content)
            assertEquals("absent", call.getValue("systemShape").jsonPrimitive.content)
            assertEquals(1, call.getValue("messageCount").jsonPrimitive.content.toInt())
            assertEquals("assistant", call.getValue("role").jsonPrimitive.content)
        }
    }

    @Test
    fun `lenient mode returns the canned reply for an unmatched openai request`() {
        withServer(engine = ScenarioEngine(lenient = true)) { client, _ ->
            assertEquals(200, client.post("/control/scenario", """{"name":"malformed-json"}""").statusCode())
            val resp = client.post("/openai/v1/chat/completions", openAiBody("an ordinary question"))
            assertEquals(200, resp.statusCode())
            assertEquals(CANNED_ASSISTANT_TEXT, openAiText(resp.body()))
            val root = Json.parseToJsonElement(resp.body()).jsonObject
            val choice = root.getValue("choices").jsonArray.single().jsonObject
            assertEquals("stop", choice.getValue("finish_reason").jsonPrimitive.content)

            val call = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray.single().jsonObject
            assertEquals("openai", call.getValue("vendor").jsonPrimitive.content)
            assertEquals("DEFAULT", call.getValue("role").jsonPrimitive.content)
            assertEquals("default", call.getValue("outcome").jsonPrimitive.content)
            assertEquals(1, call.getValue("occurrence").jsonPrimitive.content.toInt())
            assertTrue(call.getValue("promptSample").jsonPrimitive.content.contains("ordinary question"))
        }
    }
}
