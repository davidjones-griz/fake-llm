package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TokenUsageTest {
    @Test
    fun `scripted usage on every buffered format`() {
        val engine = ScenarioEngine()
        engine.loadInline(
            Scenario(
                name = "usage",
                steps =
                    listOf(
                        Step(text = "a", usage = TokenUsage(1200, 340)),
                        Step(text = "b", usage = TokenUsage(7, 8)),
                        Step(text = "c", usage = TokenUsage(30, 40)),
                        Step(text = "d", usage = TokenUsage(500, 600)),
                    ),
            ),
        )
        withServer(engine) { client, _ ->
            assertEquals(
                json("""{"input_tokens":1200,"output_tokens":340,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}"""),
                body(client.post(ANTHROPIC, anthropicBody("hi")).body())["usage"],
            )
            assertEquals(
                json("""{"prompt_tokens":7,"completion_tokens":8,"total_tokens":15}"""),
                body(client.post(OPENAI, OPENAI_BODY).body())["usage"],
            )
            assertEquals(
                json(
                    """{"input_tokens":30,"input_tokens_details":{"cached_tokens":0},"output_tokens":40,""" +
                        """"output_tokens_details":{"reasoning_tokens":0},"total_tokens":70}""",
                ),
                body(client.post(RESPONSES, responsesBody()).body())["usage"],
            )
            assertEquals(
                json("""{"promptTokenCount":500,"candidatesTokenCount":600,"totalTokenCount":1100}"""),
                body(client.post(GEMINI, GEMINI_BODY).body())["usageMetadata"],
            )
        }
    }

    @Test
    fun `scripted usage on every stream`() {
        val engine = ScenarioEngine()
        engine.loadInline(Scenario(name = "usage", steps = List(4) { Step(text = "streamed reply", usage = TokenUsage(1200, 340)) }))
        withServer(engine) { client, _ ->
            val anthropic = namedFrames(client.post(ANTHROPIC, anthropicBody("hi", stream = true)).body()).toMap()
            assertEquals(
                json("1200"),
                message(anthropic.getValue("message_start"))["message"]!!.jsonObject["usage"]!!.jsonObject["input_tokens"],
            )
            assertEquals(json("""{"output_tokens":340}"""), message(anthropic.getValue("message_delta"))["usage"])

            val openAi = dataFrames(client.post(OPENAI, OPENAI_STREAM_BODY).body())
            val usageChunk = message(openAi[openAi.size - 2])
            assertEquals(json("""{"prompt_tokens":1200,"completion_tokens":340,"total_tokens":1540}"""), usageChunk["usage"])

            val responses = namedFrames(client.post(RESPONSES, responsesBody(stream = true)).body())
            for (name in listOf("response.created", "response.in_progress")) {
                val event = responses.first { it.first == name }.second
                assertEquals(JsonNull, message(event)["response"]!!.jsonObject["usage"], name)
            }
            val completed = message(responses.first { it.first == "response.completed" }.second)
            assertEquals(
                json(
                    """{"input_tokens":1200,"input_tokens_details":{"cached_tokens":0},"output_tokens":340,""" +
                        """"output_tokens_details":{"reasoning_tokens":0},"total_tokens":1540}""",
                ),
                completed["response"]!!.jsonObject["usage"],
            )

            val gemini = dataFrames(client.post(GEMINI_STREAM, GEMINI_BODY).body()).map(::message)
            assertFalse(gemini.dropLast(1).any { "usageMetadata" in it }, "only the last chunk reports usage")
            assertEquals(
                json("""{"promptTokenCount":1200,"candidatesTokenCount":340,"totalTokenCount":1540}"""),
                gemini.last()["usageMetadata"],
            )
        }
    }

    @Test
    fun `unset counts keep the defaults`() {
        assertEquals(
            json("""{"prompt_tokens":100,"completion_tokens":4000,"total_tokens":4100}"""),
            openAiUsage(TokenUsage(outputTokens = 4000)),
        )
    }

    @Test
    fun `zero counts are allowed`() {
        assertEquals(json("""{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}"""), openAiUsage(TokenUsage(0, 0)))
    }

    @Test
    fun `the total does not overflow`() {
        val usage = openAiUsage(TokenUsage(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(json("4294967294"), usage!!.jsonObject["total_tokens"])
    }

    @Test
    fun `a step without usage is unchanged`() {
        val engine = ScenarioEngine()
        engine.loadInline(Scenario(name = "plain", steps = List(4) { Step(text = "x") }))
        withServer(engine) { client, _ ->
            assertEquals(
                json("""{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}"""),
                body(client.post(ANTHROPIC, anthropicBody("hi")).body())["usage"],
            )
            assertEquals(
                json("""{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}"""),
                body(client.post(OPENAI, OPENAI_BODY).body())["usage"],
            )
            assertEquals(json("150"), body(client.post(RESPONSES, responsesBody()).body())["usage"]!!.jsonObject["total_tokens"])
            assertEquals(
                json("""{"promptTokenCount":100,"candidatesTokenCount":50,"totalTokenCount":150}"""),
                body(client.post(GEMINI, GEMINI_BODY).body())["usageMetadata"],
            )
        }
    }

    @Test
    fun `the cache figures do not follow usage`() {
        val engine = ScenarioEngine()
        engine.loadInline(Scenario(name = "cached", steps = listOf(Step(text = "x", usage = TokenUsage(1, 2)))))
        withServer(engine) { client, _ ->
            val request =
                """{"model":"m","max_tokens":64,"system":[{"type":"text","text":"sys","cache_control":{"type":"ephemeral"}}],""" +
                    """"messages":[{"role":"user","content":"hi"}]}"""
            assertEquals(
                json("""{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":10,"cache_creation_input_tokens":20}"""),
                body(client.post(ANTHROPIC, request).body())["usage"],
            )
        }
    }

    @Test
    fun `TokenUsage rejects a negative count`() {
        val input = assertFailsWith<IllegalArgumentException> { TokenUsage(inputTokens = -1) }
        assertContains(input.message.orEmpty(), "usage.inputTokens must not be negative, got -1")
        val output = assertFailsWith<IllegalArgumentException> { TokenUsage(outputTokens = -2) }
        assertContains(output.message.orEmpty(), "usage.outputTokens must not be negative, got -2")
    }

    @Test
    fun `Step rejects usage on an error step`() {
        val failure = assertFailsWith<IllegalArgumentException> { Step(error = ErrorStep(status = 500), usage = TokenUsage()) }
        assertContains(failure.message.orEmpty(), "usage is only valid on a step with text or toolCalls")
    }

    private fun openAiUsage(usage: TokenUsage): JsonElement? {
        val engine = ScenarioEngine()
        engine.loadInline(Scenario(name = "one", steps = listOf(Step(text = "x", usage = usage))))
        var result: JsonElement? = null
        withServer(engine) { client, _ -> result = body(client.post(OPENAI, OPENAI_BODY).body())["usage"] }
        return result
    }

    private fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun message(data: String): JsonObject = body(data)

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private companion object {
        const val ANTHROPIC = "/anthropic/v1/messages"
        const val OPENAI = "/openai/v1/chat/completions"
        const val RESPONSES = "/openai/v1/responses"
        const val GEMINI = "/gemini/v1beta/models/m:generateContent"
        const val GEMINI_STREAM = "/gemini/v1beta/models/m:streamGenerateContent?alt=sse"
        const val OPENAI_BODY = """{"model":"m","messages":[{"role":"user","content":"hi"}]}"""
        const val OPENAI_STREAM_BODY =
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":true}}"""
        const val GEMINI_BODY = """{"contents":[{"parts":[{"text":"hi"}]}]}"""
    }
}
