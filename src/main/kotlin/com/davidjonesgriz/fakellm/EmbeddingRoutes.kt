// The embeddings routes: OpenAI `POST /openai/v1/embeddings` and Gemini `:embedContent` and
// `:batchEmbedContents`. They are not calls: no step, not logged, the same with or without a
// scenario and in lenient mode.
package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Answers an embeddings request, or returns false when the request is not one. Invalid bodies throw (the `exception` 400). */
internal fun embeddingRoute(ex: HttpExchange): Boolean {
    val path = ex.requestURI.path
    val body =
        when {
            ex.requestMethod != "POST" -> null
            path == OPENAI_EMBEDDINGS -> openAiEmbeddings(readJsonObject(ex))
            GEMINI_EMBED.matches(path) -> geminiEmbedContent(readJsonObject(ex))
            GEMINI_BATCH_EMBED.matches(path) -> geminiBatchEmbed(readJsonObject(ex))
            else -> null
        }
    body?.let { respond(ex, HTTP_OK, it.toString()) }
    return body != null
}

private fun readJsonObject(ex: HttpExchange): JsonObject =
    Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()) as? JsonObject
        ?: error("the request body must be a JSON object")

/** The OpenAI embeddings list. Every check runs before any vector is built. */
private fun openAiEmbeddings(obj: JsonObject): JsonObject {
    val inputs = openAiInputs(obj.valueOrNull("input"))
    require(inputs.size <= MAX_EMBEDDING_INPUTS) { "input has ${inputs.size} inputs; at most $MAX_EMBEDDING_INPUTS are allowed" }
    val dimensions = dimensionsOf(obj.valueOrNull("dimensions"), "dimensions")
    requireDimensions(dimensions)
    val format =
        obj.valueOrNull("encoding_format")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "$it" } ?: "float"
    require(format == "float" || format == "base64") { "encoding_format must be \"float\" or \"base64\", got $format" }
    requireValueTotal(List(inputs.size) { dimensions }, "input", "dimensions")
    val tokens = inputs.sumOf { it.second }
    return buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            inputs.forEachIndexed { i, (text, _) ->
                val values = fakeEmbedding(text, dimensions)
                addJsonObject {
                    put("object", "embedding")
                    put("index", i)
                    if (format == "base64") put("embedding", base64Floats(values)) else put("embedding", floatsJson(values))
                }
            }
        }
        put("model", obj.stringField("model").orEmpty())
        putJsonObject("usage") {
            put("prompt_tokens", tokens)
            put("total_tokens", tokens)
        }
    }
}

/**
 * The (text to hash, token estimate) of each input: a string, an array of strings, one token array
 * or an array of token arrays. A token array is hashed as its canonical JSON text (`[1,2,3]`) and
 * counts its length.
 */
private fun openAiInputs(input: JsonElement?): List<Pair<String, Int>> {
    val string = { el: JsonElement ->
        val text = (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("an input must be a string, got $el")
        require(text.isNotEmpty()) { "an input must not be an empty string" }
        text to estimatedTokens(text)
    }
    val tokenArray = { el: JsonElement ->
        val tokens = (el as? JsonArray)?.map { intOf(it) ?: error("a token must be an integer, got $it") }
        require(!tokens.isNullOrEmpty()) { "a token array must be a non-empty array of integers, got $el" }
        tokens.joinToString(",", "[", "]") to tokens.size
    }
    return when {
        input is JsonPrimitive && input.isString -> listOf(string(input))
        input !is JsonArray || input.isEmpty() -> error("input must be a non-empty string or array, got $input")
        input.all { it is JsonPrimitive && it.isString } -> input.map(string)
        input.any { it is JsonPrimitive && it.isString } -> error("input must not mix strings, tokens and token arrays")
        input.all { it is JsonPrimitive } -> listOf(tokenArray(input))
        input.all { it is JsonArray } -> input.map(tokenArray)
        else -> error("input must not mix strings, tokens and token arrays")
    }
}

/** Gemini `embedContent`: `{"embedding":{"values":[...]}}` for the body's own `content`. */
private fun geminiEmbedContent(obj: JsonObject): JsonObject {
    val (text, size) = geminiValues(obj, "request")
    requireDimensions(size, "outputDimensionality")
    val values = fakeEmbedding(text, size)
    return buildJsonObject { putJsonObject("embedding") { put("values", floatsJson(values)) } }
}

/** Gemini `batchEmbedContents`: one `{"values":[...]}` per request, in order. Every request is read and checked first. */
private fun geminiBatchEmbed(obj: JsonObject): JsonObject {
    val requests = obj.valueOrNull("requests") as? JsonArray ?: error("requests must be an array")
    require(requests.isNotEmpty()) { "requests must not be empty" }
    val read = requests.mapIndexed { i, request -> geminiValues(request, "requests[$i]") }
    requireValueTotal(read.map { it.second }, "requests", "outputDimensionality")
    return buildJsonObject {
        putJsonArray("embeddings") {
            read.forEach { (text, size) -> addJsonObject { put("values", floatsJson(fakeEmbedding(text, size))) } }
        }
    }
}

/**
 * One Gemini request's (text, size): the `text` of its `content`'s parts joined with a newline, and
 * `outputDimensionality`. A part's `text` must be a non-empty string; parts without `text` and other
 * keys (`model`, `taskType`, `title`, `role`) are ignored.
 */
private fun geminiValues(
    request: JsonElement,
    at: String,
): Pair<String, Int> {
    val content = ((request as? JsonObject)?.valueOrNull("content") as? JsonObject) ?: error("$at: content must be an object")
    val texts =
        (content["parts"] as? JsonArray).orEmpty().mapNotNull { part ->
            (part as? JsonObject)?.get("text")?.let { text ->
                (text as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                    ?: error("$at: a part's text must be a non-empty string, got $text")
            }
        }
    require(texts.isNotEmpty()) { "$at: content has no text part" }
    return texts.joinToString("\n") to dimensionsOf(request.valueOrNull("outputDimensionality"), "$at: outputDimensionality")
}

/** A size field: absent or null gives the default; otherwise it must be an integer. The range is checked by [requireDimensions]. */
private fun dimensionsOf(
    value: JsonElement?,
    field: String,
): Int = if (value == null) DEFAULT_EMBEDDING_DIMENSIONS else intOf(value) ?: error("$field must be an integer, got $value")

/** An integer as the spec reads one: a JSON number, not a string, with no fraction or exponent. */
private fun intOf(value: JsonElement): Int? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

/**
 * Each value as the float32's exact double, so a client that reads JSON numbers as float64 gets the
 * same values as from the base64 form.
 */
private fun floatsJson(values: FloatArray): JsonArray = JsonArray(values.map { JsonPrimitive(it.toDouble()) })

private const val OPENAI_EMBEDDINGS = "/openai/v1/embeddings"
private val GEMINI_EMBED = Regex("/gemini/[^/]+/models/[^/:]+:embedContent")
private val GEMINI_BATCH_EMBED = Regex("/gemini/[^/]+/models/[^/:]+:batchEmbedContents")
