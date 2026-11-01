// Where a loaded scenario has got to, and which of its steps a call can take: the step at the
// position, the steps of the parallel step or overlap group there, and the windows after it that
// the call can reach once their minimums allow.
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
 * Where a scenario has got to: [pos], the index of the next step it has not moved past, and
 * [counts], how many calls each step at or after [pos] has taken, keyed by (step, inner step).
 *
 * A step takes from its minimum to its maximum number of calls (exactly one without [Step.times]).
 * A call can take an open step (below its maximum) in the window at [pos]: the plain step there, the
 * steps of the parallel step there, or of the whole overlap group there. Once every step in that
 * window has met its minimum, a call can also reach the window after it, and so on through windows
 * whose steps all have a minimum of 0. The window at [pos] is tried first.
 */
internal class Position {
    private var pos = 0
    private val counts = mutableMapOf<Pair<Int, Int?>, Int>()

    /** The responses in reach in [s], the open ones, and the first open one whose match holds for the call. */
    fun choose(
        s: Scenario?,
        vendor: String,
        req: ParsedRequest,
    ): Choice {
        val reach = s?.takeIf { pos < it.steps.size }?.let { reach(it) }.orEmpty()
        val open = reach.filter { it.isOpen() }
        return Choice(reach, open, open.firstOrNull { it.step.match?.holds(vendor, req) ?: true })
    }

    /** The windows from [pos] on: each one while every step in the one before it has met its minimum. */
    private fun reach(s: Scenario): List<Candidate> {
        val reach = mutableListOf<Candidate>()
        var start = pos
        while (start < s.steps.size) {
            val end = windowEnd(s, start)
            val window = (start until end).flatMap { top -> candidatesOf(s.steps[top], top) }
            reach += window
            if (!window.all { it.isMet() }) break
            start = end
        }
        return reach
    }

    /** The index just past the window at [start]: one step, unless it starts an overlap group. */
    private fun windowEnd(
        s: Scenario,
        start: Int,
    ): Int {
        var end = start + 1
        if (s.steps[start].parallel?.overlap == true) {
            while (end < s.steps.size && s.steps[end].parallel?.overlap == true) end++
        }
        return end
    }

    private fun Candidate.count(): Int = counts[top to inner] ?: 0

    private fun Candidate.isOpen(): Boolean = step.maxUses?.let { count() < it } ?: true

    private fun Candidate.isMet(): Boolean = count() >= step.minUses

    /**
     * Gives [chosen] one more call: moves past the windows before its own (their minimums are met),
     * counts the call, and moves past its window once every step in it is at its maximum.
     */
    fun take(
        s: Scenario,
        chosen: Candidate,
    ): Taken {
        while (windowEnd(s, pos) <= chosen.top) pos = windowEnd(s, pos)
        counts.keys.removeAll { it.first < pos }
        val uses = chosen.count() + 1
        counts[chosen.top to chosen.inner] = uses
        val end = windowEnd(s, pos)
        if ((pos until end).flatMap { top -> candidatesOf(s.steps[top], top) }.none { it.isOpen() }) {
            pos = end
            counts.clear()
        }
        return Taken(chosen, uses.takeIf { chosen.step.times != null })
    }

    /** The 1-based numbers of [s]'s steps from [pos] on that have a step below its minimum. */
    fun unused(s: Scenario): List<Int> =
        (pos until s.steps.size).filter { top -> candidatesOf(s.steps[top], top).any { !it.isMet() } }.map { it + 1 }
}

/** A call's step ([candidate]) and, for a step with [Step.times], which of its uses this was. */
internal data class Taken(
    val candidate: Candidate,
    val useNumber: Int?,
)

/** The fewest calls a step must get: [Times.min], or 1 without `times`. */
private val Step.minUses: Int get() = times?.min ?: 1

/** The most calls a step can take: [Times.max] (null is unbounded), or 1 without `times`. */
private val Step.maxUses: Int? get() = if (times == null) 1 else times.max

/** The responses in reach of a call ([window]), the open ones ([open]), and the one it takes, if any. */
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
