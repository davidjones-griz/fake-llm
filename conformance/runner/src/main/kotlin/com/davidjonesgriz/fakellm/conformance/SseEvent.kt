package com.davidjonesgriz.fakellm.conformance

/** One Server-Sent Event: its `event:` name and its `data:` lines joined with newlines. */
data class SseEvent(
    val event: String,
    val data: String,
)

/**
 * Parses an SSE body into events, as an SSE client dispatches them. Frames end with a blank line,
 * and a final frame without one is discarded; `:` comment lines are skipped; several `data:` lines
 * join with `\n`; a frame without `event:` is named `message`; a frame with no `data:` line (only
 * `retry:` or `id:`, say) dispatches nothing.
 */
fun parseSse(body: String): List<SseEvent> {
    val frames = body.replace("\r\n", "\n").split("\n\n")
    // The text after the last blank line is an unterminated frame (or empty).
    return frames.dropLast(1).mapNotNull { frame ->
        val lines = frame.lines().filter { it.isNotEmpty() && !it.startsWith(":") }
        val data = lines.filter { it.startsWith("data:") }
        if (data.isEmpty()) return@mapNotNull null
        val event = lines.firstOrNull { it.startsWith("event:") }?.let { field(it, "event:") } ?: "message"
        SseEvent(event, data.joinToString("\n") { field(it, "data:") })
    }
}

private fun field(
    line: String,
    name: String,
): String = line.removePrefix(name).removePrefix(" ")

/**
 * Whether [actual], a response header value, matches [expected]. Values match exactly, except
 * that an expected `content-type` without parameters matches by media type, case-insensitively.
 */
fun headerMatches(
    name: String,
    expected: String,
    actual: String,
): Boolean =
    if (name.equals("content-type", ignoreCase = true) && ';' !in expected) {
        actual.substringBefore(';').trim().equals(expected.trim(), ignoreCase = true)
    } else {
        actual == expected
    }
