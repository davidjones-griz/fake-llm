// Comparing and reading JSON values for a step's `match`.
package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A tool result's text: a string's value, else the compact JSON, else empty when there is none. */
internal fun resultText(content: JsonElement?): String =
    when {
        content == null -> ""
        content is JsonPrimitive && content.isString -> content.content
        else -> content.toString()
    }

/** JSON equality, with numbers compared by value, so `1` equals `1.0`. */
internal fun jsonEquals(
    a: JsonElement,
    b: JsonElement,
): Boolean =
    when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { jsonEquals(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive -> primitiveEquals(a, b)
        else -> false
    }

private fun primitiveEquals(
    a: JsonPrimitive,
    b: JsonPrimitive,
): Boolean {
    val x = a.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
    val y = b.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
    return if (x != null && y != null) x.compareTo(y) == 0 else a == b
}
