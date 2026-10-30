// Deterministic fake embeddings: a unit-length float32 vector derived from SHA-256 of the text, so
// the same text and size always give the same vector. No semantic similarity.
package com.davidjonesgriz.fakellm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.sqrt

/** Request size range and default, on every vendor. */
internal const val MAX_EMBEDDING_DIMENSIONS = 8192
internal const val DEFAULT_EMBEDDING_DIMENSIONS = 1536

/** At most this many inputs in one OpenAI request, OpenAI's own limit. */
internal const val MAX_EMBEDDING_INPUTS = 2048

/** At most this many values in one response (2048 × 1536), so one request's memory is bounded. */
internal const val MAX_EMBEDDING_VALUES = 3_145_728L

/**
 * The vector for [text]: component i is the signed big-endian int at offset (i % 8) * 4 of
 * SHA-256(UTF-8 [text] + (i / 8) as 4 big-endian bytes), over 2^31, then the whole vector is
 * divided by its L2 norm.
 */
internal fun fakeEmbedding(
    text: String,
    dimensions: Int,
): FloatArray {
    requireDimensions(dimensions)
    val textBytes = text.toByteArray(Charsets.UTF_8)
    val raw = DoubleArray(dimensions)
    // digest() resets the instance, so one serves every block.
    val digest = MessageDigest.getInstance("SHA-256")
    var block = ByteBuffer.allocate(0)
    for (i in 0 until dimensions) {
        if (i % INTS_PER_BLOCK == 0) {
            digest.update(textBytes)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(i / INTS_PER_BLOCK).array())
            block = ByteBuffer.wrap(digest.digest())
        }
        raw[i] = block.getInt((i % INTS_PER_BLOCK) * Int.SIZE_BYTES) / TWO_TO_THE_31
    }
    val norm = sqrt(raw.sumOf { it * it })
    return if (norm == 0.0) {
        FloatArray(dimensions) { if (it == 0) 1f else 0f }
    } else {
        FloatArray(dimensions) { (raw[it] / norm).toFloat() }
    }
}

/** [values] as little-endian float32 bytes, base64-encoded: the OpenAI `encoding_format: "base64"` form. */
internal fun base64Floats(values: FloatArray): String {
    val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { buffer.putFloat(it) }
    return Base64.getEncoder().encodeToString(buffer.array())
}

/** A rough token estimate for usage: one token per four code points, at least one. */
internal fun estimatedTokens(text: String): Int = maxOf(1, (text.codePointCount(0, text.length) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN)

/** The size range, on every route: from 1 to [MAX_EMBEDDING_DIMENSIONS]. [field] names the request field the size came from. */
internal fun requireDimensions(
    dimensions: Int,
    field: String = "dimensions",
) {
    require(dimensions in 1..MAX_EMBEDDING_DIMENSIONS) { "$field must be from 1 to $MAX_EMBEDDING_DIMENSIONS, got $dimensions" }
}

/**
 * Checks a response's sizes before any vector is built: each is in range (named as
 * `list[i].field`), then their total is at most [MAX_EMBEDDING_VALUES]. The sum is a [Long] and
 * stops at the cap, so neither a negative size nor a very long list can get past it.
 */
internal fun requireValueTotal(
    sizes: List<Int>,
    list: String,
    field: String,
) {
    sizes.forEachIndexed { i, size -> requireDimensions(size, "$list[$i].$field") }
    var total = 0L
    for (size in sizes) {
        total += size
        require(total <= MAX_EMBEDDING_VALUES) {
            "the response would hold more than $MAX_EMBEDDING_VALUES values (each input's size, summed); " +
                "send fewer inputs or smaller sizes"
        }
    }
}

private const val INTS_PER_BLOCK = 8
private const val CHARS_PER_TOKEN = 4
private const val TWO_TO_THE_31 = 2147483648.0
