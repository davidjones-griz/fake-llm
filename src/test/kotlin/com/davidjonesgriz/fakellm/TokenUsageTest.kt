package com.davidjonesgriz.fakellm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class TokenUsageTest {
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
}
