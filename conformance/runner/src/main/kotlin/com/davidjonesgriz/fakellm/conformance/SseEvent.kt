package com.davidjonesgriz.fakellm.conformance

/** One Server-Sent Event: its `event:` name and its `data:` lines joined with newlines. */
data class SseEvent(
    val event: String,
    val data: String,
)

/**
 * Parses an SSE body into events. Frames are separated by a blank line; `:` comment lines are
 * skipped; several `data:` lines join with `\n`; a frame without `event:` is named `message`.
 */
fun parseSse(body: String): List<SseEvent> =
    body
        .replace("\r\n", "\n")
        .split("\n\n")
        .mapNotNull { frame ->
            val lines = frame.lines().filter { it.isNotEmpty() && !it.startsWith(":") }
            if (lines.isEmpty()) return@mapNotNull null
            val event = lines.firstOrNull { it.startsWith("event:") }?.let { field(it, "event:") } ?: "message"
            val data = lines.filter { it.startsWith("data:") }.joinToString("\n") { field(it, "data:") }
            SseEvent(event, data)
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
