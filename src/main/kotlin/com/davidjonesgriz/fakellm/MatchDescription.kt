// How the no-match message shows a step's `match`: each key that is set, joined with "and".
package com.davidjonesgriz.fakellm

/** The match as the no-match message shows it: each key that is set, joined with "and". */
internal fun Match.describe(): String =
    listOfNotNull(
        route?.let { "route is ${jsonQuote(it)}" },
        model?.let { "model is ${jsonQuote(it)}" },
        stream?.let { "stream is $it" },
        system?.let { "system ${it.describe()}" },
        lastUser?.let { "lastUser ${it.describe()}" },
        prompt?.let { "prompt ${it.describe()}" },
        message?.describe(),
        messageCount?.let { "messageCount is $it" },
        hasSystem?.let { "hasSystem is $it" },
        toolResult?.describe(),
        tools?.describe(),
        body?.let { "body ${it.path} equals ${it.equalTo}" },
        header?.let { "header ${it.name} ${it.text.describe()}" },
    ).joinToString(" and ")

private fun MessageMatch.describe(): String =
    (if (index != null) "message[$index]" else "message(role ${jsonQuote(role!!)})") + " ${text.describe()}"

private fun ToolResultMatch.describe(): String {
    val parts =
        listOfNotNull(
            id?.let { "id ${jsonQuote(it)}" },
            name?.let { "name ${jsonQuote(it)}" },
            content?.let { "content ${it.describe()}" },
            isError?.let { "isError $it" },
        )
    return "toolResult(${parts.joinToString(", ")})"
}

private fun ToolsMatch.describe(): String {
    val parts =
        listOfNotNull(
            declares?.let { "declares ${jsonQuote(it)}" },
            choice?.let { "choice ${jsonQuote(it)}" },
            choiceTool?.let { "choiceTool ${jsonQuote(it)}" },
        )
    return "tools(${parts.joinToString(", ")})"
}
