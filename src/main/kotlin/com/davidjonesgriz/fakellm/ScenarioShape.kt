package com.davidjonesgriz.fakellm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks a scenario's JSON shape against the model's serial descriptors before kotlinx decodes it,
 * so a problem is reported with the path of the field at fault, such as
 * `$.steps[1].error.headers['retry-after']: expected a string, got 2`, instead of kotlinx's message.
 * Following the descriptors means a field added to the model needs no change here.
 *
 * The check is stricter than kotlinx in one way, as the OpenAPI spec says: an integer is a JSON
 * number written without quotes, a fraction or an exponent, within the field's range.
 */
internal object ScenarioShape {
    /** Throws [IllegalArgumentException] naming the first shape problem in [element], if there is one. */
    fun require(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
    ) {
        problem(element, descriptor, path)?.let { throw IllegalArgumentException(it) }
    }

    /** The first shape problem in [element] as a [descriptor] value at [path], or null when there is none. */
    fun problem(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
    ): String? =
        when {
            element is JsonNull -> if (descriptor.isNullable) null else "$path: must not be null"
            descriptor.kind == StructureKind.CLASS -> objectProblem(element, descriptor, path)
            descriptor.kind == StructureKind.LIST -> listProblem(element, descriptor, path)
            descriptor.kind == StructureKind.MAP -> mapProblem(element, descriptor, path)
            else -> primitiveProblem(element, descriptor.kind, path)
        }

    private fun objectProblem(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
    ): String? {
        if (element !is JsonObject) return expected("an object", element, path)
        val unknown = element.keys.firstOrNull { descriptor.getElementIndex(it) == CompositeDecoder.UNKNOWN_NAME }
        return if (unknown != null) {
            "$path: unknown field '${quoted(unknown)}'"
        } else {
            (0 until descriptor.elementsCount).firstNotNullOfOrNull { i ->
                val name = descriptor.getElementName(i)
                val value = element[name]
                when {
                    value != null -> problem(value, descriptor.getElementDescriptor(i), "$path.$name")
                    descriptor.isElementOptional(i) -> null
                    else -> "$path: missing field '$name'"
                }
            }
        }
    }

    private fun listProblem(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
    ): String? {
        if (element !is JsonArray) return expected("an array", element, path)
        return element.withIndex().firstNotNullOfOrNull { (i, item) ->
            problem(item, descriptor.getElementDescriptor(0), "$path[$i]")
        }
    }

    private fun mapProblem(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
    ): String? {
        if (element !is JsonObject) return expected("an object", element, path)
        return element.entries.firstNotNullOfOrNull { (key, value) ->
            problem(value, descriptor.getElementDescriptor(1), "$path['${quoted(key)}']")
        }
    }

    private fun primitiveProblem(
        element: JsonElement,
        kind: SerialKind,
        path: String,
    ): String? {
        val primitive = element as? JsonPrimitive
        val valid =
            when (kind) {
                PrimitiveKind.STRING -> primitive?.isString == true
                PrimitiveKind.INT -> primitive?.isString == false && primitive.content.toIntOrNull() != null
                PrimitiveKind.LONG -> primitive?.isString == false && primitive.content.toLongOrNull() != null
                else -> error("scenario field kind $kind at $path is not supported by the shape check")
            }
        return when {
            valid -> null

            kind == PrimitiveKind.STRING -> expected("a string", element, path)

            primitive?.isString == false &&
                INTEGER_LITERAL.matches(
                    primitive.content,
                )
            -> expected(INTEGER_RANGES.getValue(kind), element, path)

            else -> expected("an integer", element, path)
        }
    }

    private fun expected(
        what: String,
        element: JsonElement,
        path: String,
    ): String = "$path: expected $what, got ${preview(element)}"

    private fun preview(element: JsonElement): String {
        val text = element.toString()
        return if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS) + "…"
    }

    /**
     * The message for a kotlinx failure that passed the shape check: a rule in the model's `init`
     * block (such as `status must be an HTTP status`) arrives as the cause, and keeps its own
     * message. Anything else falls back to the first line of kotlinx's message.
     */
    fun decodeFailure(e: IllegalArgumentException): String {
        val cause = e.cause
        return if (cause is IllegalArgumentException && cause !is SerializationException) {
            cause.message.orEmpty()
        } else {
            e.message
                .orEmpty()
                .lineSequence()
                .first()
        }
    }

    /**
     * Parses [text] as JSON. Malformed JSON throws [IllegalArgumentException] with kotlinx's first
     * line, its character offset turned into a line and column, and without the input it quotes.
     */
    fun parse(
        json: Json,
        text: String,
    ): JsonElement =
        try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            val first =
                e.message
                    .orEmpty()
                    .lineSequence()
                    .first()
            val located =
                OFFSET.find(first)?.let { match ->
                    val offset = match.groupValues[1].toInt().coerceIn(0, text.length)
                    val before = text.substring(0, offset)
                    val line = before.count { it == '\n' } + 1
                    val column = offset - before.lastIndexOf('\n')
                    first.replaceRange(match.range, "").let { "invalid JSON at line $line, column $column: $it" }
                } ?: "invalid JSON: $first"
            throw IllegalArgumentException(located, e)
        }

    private const val PREVIEW_CHARS = 60
    private val INTEGER_LITERAL = Regex("-?\\d+")
    private val INTEGER_RANGES: Map<SerialKind, String> =
        mapOf(
            PrimitiveKind.INT to "an integer from ${Int.MIN_VALUE} to ${Int.MAX_VALUE}",
            PrimitiveKind.LONG to "an integer from ${Long.MIN_VALUE} to ${Long.MAX_VALUE}",
        )
    private val OFFSET = Regex(" at offset (\\d+)")
}

/** [name] for a path, with `'` escaped, since paths quote names in `'`. */
private fun quoted(name: String): String = name.replace("'", "\\'")
