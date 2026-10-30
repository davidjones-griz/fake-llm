// Vendor requests that are not calls: Anthropic token counting and the three model lists. They
// take no step, are not logged, and answer the same with or without a scenario.
package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder

/** The one model every list reports. A lookup by id answers for any id. */
internal const val FAKE_MODEL_ID = "fake-llm"

/**
 * Answers `POST /anthropic/v1/messages/count_tokens` and the model list and lookup routes.
 * Returns false when the request is none of them. A count_tokens body the Messages parser rejects
 * throws, which `route` answers with the `invalid-json` or `invalid-request` 400.
 */
internal fun infoRoute(ex: HttpExchange): Boolean {
    val path = ex.requestURI.path
    val body =
        when (ex.requestMethod) {
            "POST" -> if (path == COUNT_TOKENS_PATH) countTokensBody(ex) else null
            "GET" -> modelsBody(path, ex.requestURI.rawPath)
            else -> null
        }
    body?.let { respond(ex, HTTP_OK, it.toString()) }
    return body != null
}

private fun countTokensBody(ex: HttpExchange): JsonObject {
    parseAnthropicRequest(ex.requestBody.readBytes().decodeToString())
    return buildJsonObject { put("input_tokens", FIXED_INPUT_TOKENS) }
}

/**
 * A list holds one model, [FAKE_MODEL_ID]; a lookup echoes the id in the path. The id is read from
 * [rawPath] and then decoded, so an id the SDK sent with an encoded `/` (`%2F`) is one id.
 */
private fun modelsBody(
    path: String,
    rawPath: String,
): JsonObject? =
    when {
        path == ANTHROPIC_MODELS -> {
            buildJsonObject {
                put("data", buildJsonArray { add(anthropicModel(FAKE_MODEL_ID)) })
                put("has_more", false)
                put("first_id", FAKE_MODEL_ID)
                put("last_id", FAKE_MODEL_ID)
            }
        }

        path == OPENAI_MODELS -> {
            buildJsonObject {
                put("object", "list")
                put("data", buildJsonArray { add(openAiModel(FAKE_MODEL_ID)) })
            }
        }

        GEMINI_MODELS.matches(path) -> {
            buildJsonObject { put("models", buildJsonArray { add(geminiModel(FAKE_MODEL_ID)) }) }
        }

        else -> {
            ANTHROPIC_MODEL.idIn(rawPath)?.let(::anthropicModel)
                ?: OPENAI_MODEL.idIn(rawPath)?.let(::openAiModel)
                // A `:` makes the path a method, such as `:generateContent`, even when it is encoded.
                ?: GEMINI_MODEL.idIn(rawPath)?.takeUnless { ':' in it }?.let(::geminiModel)
        }
    }

/** The id in [rawPath], percent-decoded; `+` stays `+`, as a path is not a form. */
private fun Regex.idIn(rawPath: String): String? =
    matchEntire(rawPath)?.groupValues?.get(1)?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }

private fun anthropicModel(id: String): JsonObject =
    buildJsonObject {
        put("type", "model")
        put("id", id)
        put("display_name", id)
        put("created_at", "1970-01-01T00:00:00Z")
        put("lifecycle", "active")
    }

private fun openAiModel(id: String): JsonObject =
    buildJsonObject {
        put("id", id)
        put("object", "model")
        put("created", 0)
        put("owned_by", FAKE_MODEL_ID)
    }

/** A Gemini model; its methods are the routes fake-llm answers for it, embeddings included. */
private fun geminiModel(model: String): JsonObject =
    buildJsonObject {
        put("name", "models/$model")
        put("version", FAKE_MODEL_ID)
        put("displayName", model)
        put(
            "supportedGenerationMethods",
            buildJsonArray {
                add("generateContent")
                add("streamGenerateContent")
                add("embedContent")
                add("batchEmbedContents")
            },
        )
    }

private const val COUNT_TOKENS_PATH = "/anthropic/v1/messages/count_tokens"
private const val ANTHROPIC_MODELS = "/anthropic/v1/models"
private const val OPENAI_MODELS = "/openai/v1/models"
private val GEMINI_MODELS = Regex("/gemini/[^/]+/models")
private val ANTHROPIC_MODEL = Regex("/anthropic/v1/models/([^/]+)")
private val OPENAI_MODEL = Regex("/openai/v1/models/([^/]+)")
private val GEMINI_MODEL = Regex("/gemini/[^/]+/models/([^/:]+)")
