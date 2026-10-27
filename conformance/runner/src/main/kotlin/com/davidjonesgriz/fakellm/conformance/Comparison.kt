package com.davidjonesgriz.fakellm.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Compares [actual] with [expected] exactly: the same JSON types, the same object keys, the same
 * array lengths and equal values. An expected integer requires an integer token, so `100.0` does
 * not match `100`. A matcher, `{"$type": "integer"}`, `{"$type": "string"}` or `{"$type": "object"}`,
 * accepts any value of that type. Returns the first difference, or null when they match.
 */
fun firstDifference(
    expected: JsonElement,
    actual: JsonElement,
    path: String = "$",
): String? {
    matcherType(expected)?.let { type ->
        return if (hasType(actual, type)) null else "$path: expected any $type, got ${describe(actual)}"
    }
    return when (expected) {
        is JsonObject -> compareObjects(expected, actual, path)
        is JsonArray -> compareArrays(expected, actual, path)
        is JsonNull -> if (actual is JsonNull) null else "$path: expected null, got ${describe(actual)}"
        is JsonPrimitive -> comparePrimitives(expected, actual, path)
    }
}

private fun compareObjects(
    expected: JsonObject,
    actual: JsonElement,
    path: String,
): String? {
    if (actual !is JsonObject) return "$path: expected an object, got ${describe(actual)}"
    return (expected.keys - actual.keys).firstOrNull()?.let { "$path: missing field '$it'" }
        ?: (actual.keys - expected.keys).firstOrNull()?.let { "$path: unexpected field '$it' = ${actual.getValue(it)}" }
        ?: expected.keys.firstNotNullOfOrNull { key -> firstDifference(expected.getValue(key), actual.getValue(key), "$path.$key") }
}

private fun compareArrays(
    expected: JsonArray,
    actual: JsonElement,
    path: String,
): String? =
    when {
        actual !is JsonArray -> "$path: expected an array, got ${describe(actual)}"
        expected.size != actual.size -> "$path: expected ${expected.size} elements, got ${actual.size}: $actual"
        else -> expected.indices.firstNotNullOfOrNull { i -> firstDifference(expected[i], actual[i], "$path[$i]") }
    }

private fun comparePrimitives(
    expected: JsonPrimitive,
    actual: JsonElement,
    path: String,
): String? {
    val matches =
        when {
            actual !is JsonPrimitive || actual is JsonNull || expected.isString != actual.isString -> false

            expected.isString || expected.content == "true" || expected.content == "false" -> expected.content == actual.content

            // Numbers: an expected integer must be written as an integer; other numbers compare by value.
            isIntegerToken(expected.content) -> expected.content == actual.content

            else -> sameNumber(expected.content, actual.content)
        }
    return if (matches) null else "$path: expected $expected, got ${describe(actual)}"
}

private fun sameNumber(
    expected: String,
    actual: String,
): Boolean {
    val e = expected.toBigDecimalOrNull()
    val a = actual.toBigDecimalOrNull()
    return e != null && a != null && e.compareTo(a) == 0
}

private fun matcherType(expected: JsonElement): String? {
    if (expected !is JsonObject || expected.keys != setOf("\$type")) return null
    return (expected.getValue("\$type") as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private fun hasType(
    actual: JsonElement,
    type: String,
): Boolean =
    when (type) {
        "object" -> actual is JsonObject
        "string" -> actual is JsonPrimitive && actual !is JsonNull && actual.isString
        "integer" -> actual is JsonPrimitive && actual !is JsonNull && !actual.isString && isIntegerToken(actual.content)
        else -> false
    }

private val INTEGER_TOKEN = Regex("-?(0|[1-9][0-9]*)")

private fun isIntegerToken(token: String): Boolean = INTEGER_TOKEN.matches(token)

private fun describe(actual: JsonElement): String =
    when (actual) {
        is JsonObject -> "an object $actual"
        is JsonArray -> "an array $actual"
        else -> actual.toString()
    }
