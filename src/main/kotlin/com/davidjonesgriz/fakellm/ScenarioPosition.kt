// Where a loaded scenario has got to, and which of its steps a call can take: the step at the
// position, or the steps of the parallel step or overlap group there.
package com.davidjonesgriz.fakellm

/**
 * A response a call could take: the scenario's step [top] (0-based), or, when [inner] is set, that
 * step of the parallel step [top].
 */
internal data class Candidate(
    val top: Int,
    val inner: Int?,
    val step: Step,
) {
    /** `step N`, or `step N.M` for a parallel step's step. */
    val label: String get() = "step ${top + 1}" + (inner?.let { ".${it + 1}" } ?: "")
}

/**
 * Where a scenario has got to: [pos], the index of the next step it has not moved past, and [used],
 * the (step, inner step) pairs used among the steps at [pos] while they are not all used.
 */
internal class Position {
    private var pos = 0
    private val used = mutableSetOf<Pair<Int, Int>>()

    /** The responses at [pos] in [s], the unused ones, and the first unused one whose match holds for the call. */
    fun choose(
        s: Scenario?,
        vendor: String,
        req: ParsedRequest,
    ): Choice {
        val window = s?.takeIf { pos < it.steps.size }?.let { window(it) }.orEmpty()
        val open = window.filter { it.isOpen() }
        return Choice(window, open, open.firstOrNull { it.step.match?.holds(vendor, req) ?: true })
    }

    /**
     * The responses at [pos] in [s]: the plain step there, the steps of the parallel step there, or,
     * when it sets `overlap`, the steps of every consecutive parallel step from there that sets it.
     */
    private fun window(s: Scenario): List<Candidate> = (pos until windowEnd(s)).flatMap { top -> candidatesOf(s.steps[top], top) }

    /** The index just past the steps at [pos]: one step, unless they are an overlap group. */
    private fun windowEnd(s: Scenario): Int {
        var end = pos + 1
        if (s.steps[pos].parallel?.overlap == true) {
            while (end < s.steps.size && s.steps[end].parallel?.overlap == true) end++
        }
        return end
    }

    private fun Candidate.isOpen(): Boolean = inner == null || (top to inner) !in used

    /** Marks [chosen] used, and moves past the steps at [pos] once every one in [window] is. */
    fun take(
        chosen: Candidate,
        window: List<Candidate>,
    ) {
        chosen.inner?.let { used += chosen.top to it }
        if (chosen.inner == null || window.none { it.isOpen() }) {
            pos = window.last().top + 1
            used.clear()
        }
    }

    /** The 1-based numbers of [s]'s steps not used up: the steps at [pos] with an unused part, and every step after them. */
    fun unused(s: Scenario): List<Int> {
        if (pos >= s.steps.size) return emptyList()
        val end = windowEnd(s)
        val open = (pos until end).filter { top -> candidatesOf(s.steps[top], top).any { it.isOpen() } }
        return (open + (end until s.steps.size)).map { it + 1 }
    }
}

/** The responses a call could take ([window]), the unused ones ([open]), and the one it takes, if any. */
internal data class Choice(
    val window: List<Candidate>,
    val open: List<Candidate>,
    val chosen: Candidate?,
)

/** A call's outcome from what it chose: the served step's kind, or why no step served it. */
internal fun outcomeOf(
    choice: Choice,
    s: Scenario?,
    lenient: Boolean,
): String =
    when {
        choice.chosen != null -> stepOutcome(choice.chosen.step)
        s == null -> if (lenient) "default" else "no-scenario"
        choice.window.isEmpty() -> "no-more-steps"
        else -> "no-match"
    }

/** The responses [step], the scenario's step [top], holds: itself, or its parallel steps. */
internal fun candidatesOf(
    step: Step,
    top: Int,
): List<Candidate> = step.parallel?.steps?.mapIndexed { i, inner -> Candidate(top, i, inner) } ?: listOf(Candidate(top, null, step))

/** A served call's outcome: `error:<status>`, `tool-calls` or `text`. */
private fun stepOutcome(step: Step): String =
    when {
        step.error != null -> "error:${step.error.status}"
        step.toolCalls != null -> "tool-calls"
        else -> "text"
    }
