// The parts of a scenario for calls that arrive in parallel: a parallel step's section, and the
// `match` a step can carry. A call takes a step only when the step's match holds for it.
package com.davidjonesgriz.fakellm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.regex.PatternSyntaxException

/**
 * The responses of a parallel step: one position in the scenario whose [steps] calls use in any
 * order. A call takes the first unused step whose [Step.match] it satisfies, in listed order, and
 * the scenario moves past the section once every step in it is used. Consecutive sections with
 * [overlap] form one group, searched together: see the scenario reference.
 *
 * @throws IllegalArgumentException when [steps] is empty or one of them is itself a parallel step.
 *   The message text is diagnostic, not part of the API.
 */
@Serializable
public data class ParallelSection(
    val steps: List<Step>,
    /** Joins this section with the consecutive sections after it that also set it, into one group. */
    val overlap: Boolean = false,
) {
    init {
        require(steps.isNotEmpty()) { "a parallel step's steps must not be empty" }
        require(steps.none { it.parallel != null }) { "a parallel step cannot contain another parallel step" }
    }
}

/**
 * What a call must look like for a step to answer it. Every key that is set must hold; at least one
 * must be set. See the scenario reference for what each key reads on each route.
 *
 * @throws IllegalArgumentException when no key is set, [route] is not a route, or [messageCount] is
 *   negative. The message text is diagnostic, not part of the API.
 */
@Serializable
public data class Match(
    /** The route: `anthropic`, `openai` (Chat Completions), `openai-responses` or `gemini`. */
    val route: String? = null,
    /** The request's model, exactly. */
    val model: String? = null,
    /** Whether the request asked for a stream. */
    val stream: Boolean? = null,
    /** The system prompt's text. */
    val system: TextMatch? = null,
    /** The text of the last user message that has text. */
    val lastUser: TextMatch? = null,
    /** The request's combined prompt text, of which the call log's `promptSample` is the end. */
    val prompt: TextMatch? = null,
    /** One message, by index or by role. */
    val message: MessageMatch? = null,
    /** The number of messages. */
    val messageCount: Int? = null,
    /** Whether the request has a system prompt. */
    val hasSystem: Boolean? = null,
    /** A tool result the request sends back. */
    val toolResult: ToolResultMatch? = null,
    /** The declared tools and the tool choice. */
    val tools: ToolsMatch? = null,
    /** A value in the request body. */
    val body: BodyMatch? = null,
    /** A request header. */
    val header: HeaderMatch? = null,
) {
    init {
        require(
            listOf(route, model, stream, system, lastUser, prompt, message, messageCount, hasSystem, toolResult, tools, body, header)
                .any { it != null },
        ) { "a match must set at least one key" }
        require(route == null || route in ROUTES) {
            "route must be anthropic, openai, openai-responses or gemini, got '$route'"
        }
        require(messageCount == null || messageCount >= 0) { "messageCount must not be negative, got $messageCount" }
    }
}

/**
 * A rule on a text: exactly one of [contains], [equalTo] (`equals` in JSON) and [regex]. Matching is
 * case-sensitive. [regex] is a Java regular expression that may match anywhere in the text; `^` and
 * `$` anchor it.
 *
 * @throws IllegalArgumentException when not exactly one is set, or [regex] does not compile. The
 *   message text is diagnostic, not part of the API.
 */
@Serializable
public data class TextMatch(
    val contains: String? = null,
    @SerialName("equals") val equalTo: String? = null,
    val regex: String? = null,
) {
    init {
        require(listOfNotNull(contains, equalTo, regex).size == 1) { "a text match sets exactly one of contains, equals and regex" }
    }

    @Transient
    private val compiled: Regex? = regex?.let(::compileRegex)

    internal fun matches(text: String): Boolean =
        when {
            contains != null -> contains in text
            equalTo != null -> text == equalTo
            else -> compiled!!.containsMatchIn(text)
        }

    /** The rule as the no-match message shows it, such as `contains "doc-b"`. */
    internal fun describe(): String =
        when {
            contains != null -> "contains ${jsonQuote(contains)}"
            equalTo != null -> "equals ${jsonQuote(equalTo)}"
            else -> "regex ${jsonQuote(regex!!)}"
        }
}

/**
 * A rule on one message: chosen by exactly one of [index] (0-based; a negative index counts from the
 * end, so -1 is the last) and [role] (the last message with that role, as the request spells it),
 * with exactly one of [contains], [equalTo] (`equals` in JSON) and [regex] on its text.
 *
 * @throws IllegalArgumentException when not exactly one of each is set, or [regex] does not compile.
 *   The message text is diagnostic, not part of the API.
 */
@Serializable
public data class MessageMatch(
    val index: Int? = null,
    val role: String? = null,
    val contains: String? = null,
    @SerialName("equals") val equalTo: String? = null,
    val regex: String? = null,
) {
    init {
        require((index == null) != (role == null)) { "a message match sets exactly one of index and role" }
    }

    @Transient
    internal val text: TextMatch = TextMatch(contains, equalTo, regex)
}

/**
 * A rule on the request's tool results: some result must satisfy every key that is set. At least
 * one must be set. [content] is matched against a string result's text, or else its compact JSON.
 *
 * @throws IllegalArgumentException when no key is set. The message text is diagnostic, not part of the API.
 */
@Serializable
public data class ToolResultMatch(
    val id: String? = null,
    val name: String? = null,
    val content: TextMatch? = null,
    val isError: Boolean? = null,
) {
    init {
        require(listOf(id, name, content, isError).any { it != null }) { "a toolResult match must set at least one key" }
    }
}

/**
 * A rule on the request's tools: [declares] is the name of a declared tool; [choice] is the
 * normalized tool choice (`auto`, `none`, `required`, `tool` or `other`); [choiceTool] is the forced
 * tool's name, which implies `choice` `tool`. At least one must be set.
 *
 * @throws IllegalArgumentException when no key is set, [choice] is not one of the five, [choiceTool]
 *   is empty, or [choiceTool] comes with a [choice] other than `tool`. The message text is
 *   diagnostic, not part of the API.
 */
@Serializable
public data class ToolsMatch(
    val declares: String? = null,
    val choice: String? = null,
    val choiceTool: String? = null,
) {
    init {
        require(listOf(declares, choice, choiceTool).any { it != null }) { "a tools match must set at least one key" }
        require(choice == null || choice in TOOL_CHOICES) { "tools choice must be auto, none, required, tool or other, got '$choice'" }
        require(choiceTool == null || choiceTool.isNotEmpty()) { "tools choiceTool must not be empty" }
        require(choiceTool == null || choice == null || choice == "tool") { "tools choiceTool requires choice tool, got '$choice'" }
    }
}

/**
 * A rule on the request body: the value at [path] equals [equalTo] (`equals` in JSON) as JSON, numbers
 * by value. [path] is `$` followed by one or more `.name`, `['name']` or `[n]` segments, where `n`
 * may be negative to count from the end and `\'` and `\\` are the only escapes inside quotes.
 *
 * @throws IllegalArgumentException when [path] is not in that form. The message text is diagnostic,
 *   not part of the API.
 */
@Serializable
public data class BodyMatch(
    val path: String,
    @SerialName("equals") val equalTo: JsonElement,
) {
    @Transient
    internal val segments: List<PathSegment> = parseBodyPath(path)
}

/**
 * A rule on a request header: some value of the header [name] (any case) satisfies exactly one of
 * [contains], [equalTo] (`equals` in JSON) and [regex].
 *
 * @throws IllegalArgumentException when [name] is not a header name, not exactly one rule is set, or
 *   [regex] does not compile. The message text is diagnostic, not part of the API.
 */
@Serializable
public data class HeaderMatch(
    val name: String,
    val contains: String? = null,
    @SerialName("equals") val equalTo: String? = null,
    val regex: String? = null,
) {
    init {
        require(name.matches(HEADER_TOKEN)) { "header name '$name' is not a header name" }
    }

    @Transient
    internal val text: TextMatch = TextMatch(contains, equalTo, regex)
}

/** One step of a [BodyMatch] path: an object key or an array index. */
internal sealed interface PathSegment {
    data class Key(
        val name: String,
    ) : PathSegment

    data class Index(
        val index: Int,
    ) : PathSegment
}

/** [path]'s segments; throws when it is not `$` followed by one or more `.name`, `['name']` or `[n]`. */
internal fun parseBodyPath(path: String): List<PathSegment> {
    val unsupported = "body path '$path' is not supported: use \$ followed by .name, ['name'] or [n]"
    require(path.startsWith("$") && path.length > 1) { unsupported }
    val segments = mutableListOf<PathSegment>()
    var i = 1
    while (i < path.length) {
        val match = PATH_SEGMENT.matchAt(path, i) ?: throw IllegalArgumentException(unsupported)
        val (dotted, quoted, index) = match.destructured
        segments +=
            when {
                dotted.isNotEmpty() -> PathSegment.Key(dotted)
                match.value.startsWith("['") -> PathSegment.Key(quoted.replace(QUOTE_ESCAPE) { it.value.substring(1) })
                else -> PathSegment.Index(index.toIntOrNull() ?: throw IllegalArgumentException(unsupported))
            }
        i = match.range.last + 1
    }
    return segments
}

/** `.name`, `['name']` with `\'` and `\\` escapes, or `[n]`; the schema's `BodyMatch.path` pattern is the same grammar. */
private val PATH_SEGMENT = Regex("""\.([A-Za-z_][A-Za-z0-9_-]*)|\['((?:[^'\\]|\\['\\])*)']|\[(-?[0-9]{1,9})]""")
private val QUOTE_ESCAPE = Regex("""\\['\\]""")

private fun compileRegex(pattern: String): Regex =
    try {
        Regex(pattern)
    } catch (e: PatternSyntaxException) {
        throw IllegalArgumentException("regex ${quote(pattern)} does not compile: ${e.description}", e)
    }

private fun quote(text: String): String = "'${text.replace("'", "\\'")}'"

/** [text] as a JSON string, as the no-match message quotes values. */
internal fun jsonQuote(text: String): String = JsonPrimitive(text).toString()

/** The routes a [Match] can name: the call log's vendors. */
internal val ROUTES = setOf("anthropic", "openai", RESPONSES_VENDOR, "gemini")

private val TOOL_CHOICES = setOf("auto", "none", "required", "tool", "other")

/** An HTTP header name: one or more token characters (RFC 9110). */
private val HEADER_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
