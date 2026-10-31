// What answers a call that no step served: fake-llm's own 400s for a call (no-scenario,
// no-more-steps and no-match), or the lenient canned reply.
package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/** What answers a call no step served: the no-more-steps, no-match or no-scenario 400, or the lenient reply. */
internal fun unservedStep(
    served: Served,
    vendor: String,
    req: ParsedRequest,
    lenient: Boolean,
): Step {
    val n = served.seq
    val loaded = served.scenario
    return when {
        loaded != null && served.choice.window.isEmpty() -> errorStep(noMoreStepsBody(loaded, n, vendor, req))
        loaded != null -> errorStep(noMatchBody(n, vendor, req, served.choice.open))
        lenient -> Step(text = CANNED_ASSISTANT_TEXT)
        else -> errorStep(noScenarioBody(n, vendor, req))
    }
}

private fun errorStep(body: String): Step = Step(error = ErrorStep(status = HTTP_BAD_REQUEST, body = body))

private fun noScenarioBody(
    n: Long,
    vendor: String,
    req: ParsedRequest,
): String =
    errorBody(
        ErrorCode.NO_SCENARIO,
        "no scenario loaded: call $n has no step to answer it. Load a scenario with POST /control/scenario, " +
            "or start the server with --lenient for a canned reply.",
    ) { putCallFields(n, vendor, req) }

private fun noMatchBody(
    n: Long,
    vendor: String,
    req: ParsedRequest,
    open: List<Candidate>,
): String {
    val listed = open.take(NO_MATCH_LISTED).joinToString(", ") { "${it.label} (${it.step.match!!.describe()})" }
    val more = if (open.size > NO_MATCH_LISTED) " and ${open.size - NO_MATCH_LISTED} more" else ""
    return errorBody(ErrorCode.NO_MATCH, "no match: call $n matched none of the unused steps it could have taken: $listed$more") {
        putCallFields(n, vendor, req)
    }
}

private fun noMoreStepsBody(
    s: Scenario,
    n: Long,
    vendor: String,
    req: ParsedRequest,
): String =
    errorBody(
        ErrorCode.NO_MORE_STEPS,
        "no more steps: call $n came after the last step of scenario '${s.name}', which has ${s.steps.size} " +
            (if (s.steps.size == 1) "step" else "steps") + ". The last step does not repeat.",
    ) {
        put("scenario", s.name)
        put("steps", s.steps.size)
        putCallFields(n, vendor, req)
    }

private fun JsonObjectBuilder.putCallFields(
    n: Long,
    vendor: String,
    req: ParsedRequest,
) {
    put("seq", n)
    put("vendor", vendor)
    put("model", req.model)
    put("system_shape", req.systemShape)
    put("message_count", req.messageCount)
    put("prompt_preview", req.promptText.take(PROMPT_PREVIEW_CHARS))
}

private const val HTTP_BAD_REQUEST = 400

/** Characters of prompt text kept, from the start, on a 400 body's `prompt_preview`. */
private const val PROMPT_PREVIEW_CHARS = 200

/** How many unused steps the no-match message names before "and N more". */
private const val NO_MATCH_LISTED = 10
