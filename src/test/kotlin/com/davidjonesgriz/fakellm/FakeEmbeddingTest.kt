package com.davidjonesgriz.fakellm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FakeEmbeddingTest {
    @Test
    fun `a vector has the requested size and unit length`() {
        for (size in listOf(1, 2, 7, 8, 9, 1536, MAX_EMBEDDING_DIMENSIONS)) {
            val v = fakeEmbedding("some text", size)
            assertEquals(size, v.size)
            val norm = sqrt(v.sumOf { it.toDouble() * it })
            assertEquals(1.0, norm, 1e-5, "size $size")
        }
    }

    @Test
    fun `the same text and size give the same vector, and other texts other vectors`() {
        assertContentEquals(fakeEmbedding("alpha", 64), fakeEmbedding("alpha", 64))
        assertFalse(fakeEmbedding("alpha", 64).contentEquals(fakeEmbedding("beta", 64)))
    }

    @Test
    fun `the pinned vector, cross-checked with an independent script`() {
        // Python: hashlib.sha256 of the text plus the block number, struct ">i" / 2**31, normalised, float32.
        val expected = listOf(-0.14939652383327484, -0.9198163151741028, 0.3256172835826874, -0.15997493267059326)
        assertEquals(expected, fakeEmbedding("Hello", 4).map { it.toDouble() })
        assertEquals("Z/sYvhV5a79Pt6Y+eNAjvg==", base64Floats(fakeEmbedding("Hello", 4)))
        assertEquals(listOf(-1.0), fakeEmbedding("Hello", 1).map { it.toDouble() })
    }

    @Test
    fun `each block of eight components comes from its own hash`() {
        val v = fakeEmbedding("blocks", 16)
        assertFalse(v.copyOfRange(0, 8).contentEquals(v.copyOfRange(8, 16)))
        assertFalse((0 until 8).all { abs(v[it]) == abs(v[it + 8]) })
    }

    @Test
    fun `sizes from 1 to 8192 are accepted, and others rejected`() {
        fakeEmbedding("x", 1)
        fakeEmbedding("x", MAX_EMBEDDING_DIMENSIONS)
        for (size in listOf(0, -1, MAX_EMBEDDING_DIMENSIONS + 1)) {
            val e = assertFailsWith<IllegalArgumentException> { fakeEmbedding("x", size) }
            assertEquals("dimensions must be from 1 to 8192, got $size", e.message)
        }
    }

    @Test
    fun `base64Floats is the little-endian float32 bytes`() {
        val values = floatArrayOf(0.5f, -1.25f, 3.0e-7f)
        val bytes = Base64.getDecoder().decode(base64Floats(values))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals(values, FloatArray(values.size) { buffer.getFloat() })
    }

    @Test
    fun `the token estimate is a quarter of the code points, at least one`() {
        assertEquals(1, estimatedTokens(""))
        assertEquals(1, estimatedTokens("abcd"))
        assertEquals(2, estimatedTokens("abcde"))
        // Code points, not UTF-16 units: four emoji are one token.
        assertEquals(1, estimatedTokens("😀😀😀😀"))
    }

    @Test
    fun `requireValueTotal checks every size, then the total up to the cap`() {
        requireValueTotal(List(384) { MAX_EMBEDDING_DIMENSIONS }, "input", "dimensions")
        val over =
            assertFailsWith<IllegalArgumentException> { requireValueTotal(List(385) { MAX_EMBEDDING_DIMENSIONS }, "input", "dimensions") }
        assertContains(over.message.orEmpty(), "more than 3145728 values")
        // The range is checked first, by position, even after enough sizes to pass the cap.
        val sizes = List(1000) { MAX_EMBEDDING_DIMENSIONS } + -5
        val negative = assertFailsWith<IllegalArgumentException> { requireValueTotal(sizes, "requests", "outputDimensionality") }
        assertEquals("requests[1000].outputDimensionality must be from 1 to 8192, got -5", negative.message)
    }
}
