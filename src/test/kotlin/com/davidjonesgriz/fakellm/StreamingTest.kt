package com.davidjonesgriz.fakellm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamingTest {
    @Test
    fun `text shorter than chunk size yields exactly one chunk`() {
        assertEquals(listOf("hi"), chunkText("hi", chunkChars = 24))
    }

    @Test
    fun `exact multiple of chunk size yields no short remainder chunk`() {
        val chunks = chunkText("abcdefgh", chunkChars = 4)
        assertEquals(listOf("abcd", "efgh"), chunks)
    }

    @Test
    fun `non-multiple length yields a shorter final chunk, not an error`() {
        val chunks = chunkText("abcdefghi", chunkChars = 4)
        assertEquals(listOf("abcd", "efgh", "i"), chunks)
    }

    @Test
    fun `empty text yields no chunks at all`() {
        assertEquals(emptyList(), chunkText("", chunkChars = 24))
    }

    @Test
    fun `reassembly is always byte-equal to the original regardless of chunk size`() {
        val text = "The quick brown fox jumps over the lazy dog."
        listOf(1, 3, 7, 24, 1000).forEach { size ->
            assertEquals(text, chunkText(text, size).joinToString(""), "chunkChars=$size")
        }
    }

    @Test
    fun `unicode surrogate pairs are never split across chunks`() {
        // The emoji is a single code point outside the BMP — 2 UTF-16 chars, 1 surrogate pair.
        val text = "café 😀 résumé"
        val chunks = chunkText(text, chunkChars = 1)
        chunks.forEach { chunk ->
            val roundTripped = String(chunk.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            assertTrue(!roundTripped.contains('�'), "chunk '$chunk' split a surrogate pair")
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `chunkChars must be positive`() {
        assertFailsWith<IllegalArgumentException> { chunkText("x", chunkChars = 0) }
        assertFailsWith<IllegalArgumentException> { chunkText("x", chunkChars = -1) }
    }

    @Test
    fun `errorPrefixChunks yields exactly the requested count regardless of chunk size`() {
        listOf(1 to 4, 3 to 8, 5 to 1).forEach { (count, chunkChars) ->
            val chunks = errorPrefixChunks(count, chunkChars)
            assertEquals(count, chunks.size, "count=$count chunkChars=$chunkChars")
            chunks.forEach { assertTrue(it.isNotEmpty()) }
        }
    }

    @Test
    fun `errorPrefixChunks of zero or negative count yields nothing, never a negative-size chunk`() {
        assertEquals(emptyList(), errorPrefixChunks(0, 8))
        assertEquals(emptyList(), errorPrefixChunks(-1, 8))
    }
}
