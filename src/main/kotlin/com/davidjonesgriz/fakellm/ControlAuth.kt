package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange

/**
 * Bearer-token gate for the control API.
 *
 * When a token is configured, every path under `/control/` (load or unload a scenario, reset,
 * read the call and exchange logs) requires `Authorization: Bearer <token>`. `/health` stays
 * open: [needsControlAuth] is a strict path-prefix check, and `/health` does not start with
 * `/control/`, so it stays ungated even though [controlRoute] dispatches it next to the control
 * handlers.
 */
private const val CONTROL_TOKEN_VAR = "FAKE_LLM_CONTROL_TOKEN"

/**
 * Reads `FAKE_LLM_CONTROL_TOKEN`. The environment variable wins; a system property of the same
 * name is the fallback, so a caller can supply a token without changing the process environment.
 * A blank value counts as unset, which leaves auth off.
 */
internal fun readControlToken(): String? =
    (System.getenv(CONTROL_TOKEN_VAR) ?: System.getProperty(CONTROL_TOKEN_VAR))?.takeIf { it.isNotBlank() }

/** True when [path] starts with `/control/`. `/health` does not, so it is ungated. */
internal fun needsControlAuth(path: String): Boolean = path.startsWith("/control/")

internal fun isAuthorized(
    ex: HttpExchange,
    controlToken: String?,
): Boolean {
    if (controlToken == null) return true
    return ex.requestHeaders.getFirst("Authorization") == "Bearer $controlToken"
}
