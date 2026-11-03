// The run-time tag selection: which tests run, by their scenario's top-level metadata.tags.
package com.davidjonesgriz.fakellm.junit

import org.junit.jupiter.api.extension.ExtensionContext

/** The configuration parameter (or JVM system property) listing the tags a test's scenario must have one of. */
internal const val TAGS_INCLUDE = "fakellm.tags.include"

/** The configuration parameter (or JVM system property) listing the tags that skip a test whose scenario has one. */
internal const val TAGS_EXCLUDE = "fakellm.tags.exclude"

/**
 * The tags chosen at run time: a test runs when its scenario has any [include] tag (or [include]
 * is empty) and no [exclude] tag. Matching is exact and case-sensitive.
 */
internal data class TagSelection(
    val include: List<String>,
    val exclude: List<String>,
) {
    /** Whether any tag is selected; with none, every test runs and no scenario is read for its tags. */
    val isActive: Boolean get() = include.isNotEmpty() || exclude.isNotEmpty()

    /** Why a test on [scenario], whose top-level tags are [tags], is skipped; null when it runs. */
    fun skipReason(
        scenario: String,
        tags: List<String>,
    ): String? {
        val excluded = tags.firstOrNull { it in exclude }
        return when {
            excluded != null -> {
                "fake-llm scenario '$scenario' has tag '$excluded', which $TAGS_EXCLUDE ${list(exclude)} excludes"
            }

            include.isEmpty() || tags.any { it in include } -> {
                null
            }

            tags.isEmpty() -> {
                "fake-llm scenario '$scenario' has no tags, and $TAGS_INCLUDE is ${list(include)}"
            }

            else -> {
                "fake-llm scenario '$scenario' has tags ${list(tags)}, none of them in $TAGS_INCLUDE ${list(include)}"
            }
        }
    }

    companion object {
        /**
         * The selection from [context]'s configuration parameters, which JUnit also reads from JVM
         * system properties and `junit-platform.properties`.
         */
        fun of(context: ExtensionContext): TagSelection =
            TagSelection(
                include = parse(context.getConfigurationParameter(TAGS_INCLUDE).orElse(null)),
                exclude = parse(context.getConfigurationParameter(TAGS_EXCLUDE).orElse(null)),
            )

        /** [value] split on commas, each entry trimmed; blank and empty entries are ignored. */
        fun parse(value: String?): List<String> =
            value
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()

        private fun list(tags: List<String>): String = tags.joinToString(", ", "[", "]")
    }
}
