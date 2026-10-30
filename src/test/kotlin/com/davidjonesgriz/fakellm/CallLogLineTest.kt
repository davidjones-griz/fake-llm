package com.davidjonesgriz.fakellm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CallLogLineTest {
    private fun record(
        seq: Long = 2,
        vendor: String = "openai",
        model: String = "example-gpt",
        outcome: String = "error:429",
        step: Int? = 2,
    ) = CallRecord(
        seq = seq,
        vendor = vendor,
        model = model,
        systemShape = "none",
        messageCount = 1,
        outcome = outcome,
        timestampMs = 0,
        promptSample = "secret prompt",
        step = step,
    )

    @Test
    fun `formats a served call`() {
        assertEquals("fake-llm: call 2 openai model=\"example-gpt\" outcome=error:429 step=2", callLogLine(record()))
    }

    @Test
    fun `an unserved call has step -`() {
        assertEquals(
            "fake-llm: call 4 gemini model=\"example-gemini\" outcome=no-more-steps step=-",
            callLogLine(record(seq = 4, vendor = "gemini", model = "example-gemini", outcome = "no-more-steps", step = null)),
        )
    }

    @Test
    fun `a model with a quote and a newline stays on one line`() {
        val line = callLogLine(record(model = "a\"b\nc"))
        assertEquals("fake-llm: call 2 openai model=\"a\\\"b\\nc\" outcome=error:429 step=2", line)
        assertFalse('\n' in line)
    }
}
