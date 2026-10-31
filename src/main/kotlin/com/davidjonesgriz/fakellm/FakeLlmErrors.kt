// fake-llm's own error responses share one body: a stable code in `fake_llm_error`, a `message`
// saying why, and any fields the code adds (the no-more-steps and no-scenario call fields).
package com.davidjonesgriz.fakellm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The codes of fake-llm's own error responses, as `fake_llm_error` sends them. */
internal enum class ErrorCode(
    val wire: String,
) {
    NO_MORE_STEPS("no-more-steps"),
    NO_SCENARIO("no-scenario"),
    NO_MATCH("no-match"),
    INVALID_JSON("invalid-json"),
    INVALID_REQUEST("invalid-request"),
    UNSUPPORTED_STEP("unsupported-step"),
    UNKNOWN_SCENARIO("unknown-scenario"),
    INVALID_SCENARIO("invalid-scenario"),
    EXCEPTION("exception"),

    // The other statuses fake-llm sends itself, each chosen where it is sent, never by errorFor.
    UNAUTHORIZED("unauthorized"),
    NO_ROUTE("no-route"),
    NOT_IMPLEMENTED("not-implemented"),
    OPENAPI_MISSING("openapi-missing"),
}

/**
 * A request fake-llm rejects with [code]. It is an [IllegalArgumentException], so library callers
 * that catch that type see no change.
 */
internal class RequestException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** A load by name that found no scenario. An [IllegalStateException], as the loader has always thrown. */
internal class UnknownScenarioException(
    message: String,
) : IllegalStateException(message)

/** The error body for [code]: `fake_llm_error`, `message`, then the fields [extra] adds. */
internal fun errorBody(
    code: ErrorCode,
    message: String,
    extra: JsonObjectBuilder.() -> Unit = {},
): String =
    buildJsonObject {
        put("fake_llm_error", code.wire)
        put("message", message)
        extra()
    }.toString()

/**
 * The code and message for a failure the request routing caught. A [RequestException] carries its
 * own code; a JSON syntax error is `invalid-json`; another argument or state failure is a request the
 * route rejected, `invalid-request`; anything else is `exception`.
 */
internal fun errorFor(e: Throwable): Pair<ErrorCode, String> {
    val message = e.message ?: e.toString()
    val code =
        when (e) {
            is RequestException -> e.code
            is SerializationException -> ErrorCode.INVALID_JSON
            is IllegalArgumentException, is IllegalStateException -> ErrorCode.INVALID_REQUEST
            else -> ErrorCode.EXCEPTION
        }
    return code to message
}

/**
 * [body] parsed with [json] as a JSON object. Text that is not JSON, or JSON that is not an object,
 * is `invalid-json`; [notAnObject] is the message for the second.
 */
internal fun parseRequestObject(
    json: Json,
    body: String,
    notAnObject: String = "the request body must be a JSON object",
): JsonObject {
    val element =
        try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw RequestException(ErrorCode.INVALID_JSON, e.message ?: "the request body is not valid JSON", e)
        }
    return element as? JsonObject ?: throw RequestException(ErrorCode.INVALID_JSON, notAnObject)
}

/**
 * Runs a scenario [load]: an unknown name is `unknown-scenario`, and any other failure (an illegal
 * name, a file or inline scenario that does not decode, a mismatched declared name) is
 * `invalid-scenario`.
 */
internal inline fun <T> scenarioLoad(load: () -> T): T =
    try {
        load()
    } catch (e: UnknownScenarioException) {
        throw RequestException(ErrorCode.UNKNOWN_SCENARIO, e.message.orEmpty(), e)
    } catch (e: IllegalArgumentException) {
        throw RequestException(ErrorCode.INVALID_SCENARIO, e.message ?: "invalid scenario", e)
    } catch (e: IllegalStateException) {
        throw RequestException(ErrorCode.INVALID_SCENARIO, e.message ?: "invalid scenario", e)
    }
