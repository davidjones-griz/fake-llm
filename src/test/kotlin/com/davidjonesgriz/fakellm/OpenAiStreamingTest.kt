package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** The OpenAI Chat Completions stream frames, byte for byte. */
class OpenAiStreamingTest {
    private val content = buildJsonObject { put("content", "Hi.") }

    @Test
    fun `a chunk carries its delta and a null finish_reason, and no usage key unless usage was requested`() {
        assertEquals(
            """{"id":"fake-openai-3","object":"chat.completion.chunk","model":"fake-openai",""" +
                """"choices":[{"index":0,"delta":{"content":"Hi."},"finish_reason":null}]}""",
            openAiChunkJson(seq = 3, delta = content, finishReason = null, includeUsage = false),
        )
    }

    @Test
    fun `with include_usage a chunk ends with a null usage`() {
        assertEquals(
            """{"id":"fake-openai-3","object":"chat.completion.chunk","model":"fake-openai",""" +
                """"choices":[{"index":0,"delta":{"content":"Hi."},"finish_reason":null}],"usage":null}""",
            openAiChunkJson(seq = 3, delta = content, finishReason = null, includeUsage = true),
        )
    }

    @Test
    fun `the usage chunk has no choices and the fixed token counts`() {
        assertEquals(
            """{"id":"fake-openai-3","object":"chat.completion.chunk","model":"fake-openai","choices":[],""" +
                """"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}""",
            openAiUsageChunkJson(seq = 3),
        )
    }

    @Test
    fun `the finish chunk has an empty delta and the mapped finish_reason`() {
        mapOf("end_turn" to "stop", "max_tokens" to "length", "refusal" to "stop").forEach { (stopReason, finishReason) ->
            assertEquals(
                """{"id":"fake-openai-3","object":"chat.completion.chunk","model":"fake-openai",""" +
                    """"choices":[{"index":0,"delta":{},"finish_reason":"$finishReason"}]}""",
                openAiChunkJson(seq = 3, delta = buildJsonObject {}, finishReason = openAiFinishReason(stopReason), includeUsage = false),
                stopReason,
            )
        }
    }
}
