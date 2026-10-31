package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnloadRouteTest {
    private val twoSteps = """{"scenario":{"name":"two-steps","steps":[{"text":"one"},{"text":"two"}]}}"""

    private fun calls(client: TestClient): JsonArray = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray

    private fun health(client: TestClient): JsonObject = Json.parseToJsonElement(client.get("/health").body()).jsonObject

    @Test
    fun `unload removes the scenario, empties the call log, and the next call is the strict no-scenario 400`() {
        withServer { client, engine ->
            assertEquals(200, client.post("/control/scenario", twoSteps).statusCode())
            assertEquals(200, client.post("/anthropic/v1/messages", anthropicBody("hi")).statusCode())

            val unload = client.post("/control/unload", "")
            assertEquals(200, unload.statusCode())
            assertEquals(listOf("application/json"), unload.headers().allValues("content-type"))
            assertEquals("""{"unloaded":true}""", unload.body())

            assertEquals(JsonNull, health(client)["scenario"])
            assertEquals(JsonArray(emptyList()), calls(client))
            assertNull(engine.currentScenarioName())
            assertEquals(JsonArray(emptyList()), exchanges(client))

            val next = client.post("/anthropic/v1/messages", anthropicBody("hi"))
            assertEquals(400, next.statusCode())
            val body = Json.parseToJsonElement(next.body()).jsonObject
            assertEquals("no-scenario", body.getValue("fake_llm_error").jsonPrimitive.content)
            assertEquals(1, body.getValue("seq").jsonPrimitive.int)
            val record = calls(client).single().jsonObject
            assertEquals("no-scenario", record.getValue("outcome").jsonPrimitive.content)
            assertEquals(JsonNull, record.getValue("step"))
            val exchange = exchanges(client).single().jsonObject
            assertEquals(1, exchange.getValue("seq").jsonPrimitive.int)
            assertEquals(
                400,
                exchange
                    .getValue("response")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.int,
            )
        }
    }

    @Test
    fun `after unload a lenient server answers the canned reply`() {
        withServer(ScenarioEngine(lenient = true)) { client, _ ->
            assertEquals(200, client.post("/control/scenario", twoSteps).statusCode())
            assertEquals(200, client.post("/control/unload", "").statusCode())
            val next = client.post("/anthropic/v1/messages", anthropicBody("hi"))
            assertEquals(200, next.statusCode())
            assertEquals(CANNED_ASSISTANT_TEXT, anthropicText(next.body()))
            assertEquals(
                "default",
                calls(client)
                    .single()
                    .jsonObject
                    .getValue("outcome")
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `unload with nothing loaded succeeds, ignores the body, and only POST is routed`() {
        withServer { client, _ ->
            for (body in listOf("", "not json")) {
                val response = client.post("/control/unload", body)
                assertEquals(200, response.statusCode(), body)
                assertEquals("""{"unloaded":true}""", response.body(), body)
            }
            val get = client.get("/control/unload")
            assertEquals(404, get.statusCode())
            assertEquals("""{"fake_llm_error":"no route: GET /control/unload"}""", get.body())
        }
    }

    @Test
    fun `unload needs the control token when one is configured`() {
        withServer(controlToken = "t") { client, engine ->
            engine.load("hello")
            val missing = client.post("/control/unload", "")
            assertEquals(401, missing.statusCode())
            assertEquals(listOf("Bearer"), missing.headers().allValues("www-authenticate"))
            val wrong = client.post("/control/unload", "", authorization = "Bearer wrong")
            assertEquals(401, wrong.statusCode())
            assertEquals("hello", engine.currentScenarioName())
            assertEquals(200, client.post("/control/unload", "", authorization = "Bearer t").statusCode())
            assertNull(engine.currentScenarioName())
        }
    }
}
