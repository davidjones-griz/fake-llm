@file:JvmName("FakeLlmMain")

package com.davidjonesgriz.fakellm

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Command-line entry point, used by `java -jar` and the Docker image, in the class `FakeLlmMain`.
 * The JVM requires it to be public, so it is listed in `api/fake-llm.api`, but it is not meant for
 * library use: code that embeds the server calls [startFakeLlmServer] instead, which is in the class
 * `FakeLlm`.
 *
 * Deterministic, scenario-driven stand-in for vendor LLM HTTP APIs. Clients send each vendor's
 * usual JSON to this server, and the loaded scenario's steps answer the calls in order.
 *
 * Surfaces:
 *  - `POST /anthropic/v1/messages` — Anthropic Messages. `"stream": true` is answered as a
 *    Server-Sent Events stream of the Messages event sequence. Any other request is one JSON
 *    assistant message. The response is the loaded scenario's next step. A call after the last
 *    step, or a call with no scenario loaded in strict mode (the default), is HTTP 400 with a
 *    JSON body, and no SSE bytes are written. With no scenario loaded, lenient mode answers with
 *    the canned assistant reply.
 *  - `/anthropic/v1/messages/batches` — the Batches API is not implemented; any request answers 501.
 *  - `POST /openai/v1/chat/completions` — OpenAI chat completions. `"stream": true` is answered as
 *    a Chat Completions SSE stream (`chat.completion.chunk` frames, then `data: [DONE]`). Any
 *    other request is one JSON chat completion. As on the Anthropic route, a call after the last
 *    step, or a call with no scenario loaded in strict mode, is the JSON 400 with no SSE bytes.
 *  - `POST /openai/v1/responses` — OpenAI Responses. `"stream": true` is answered as a Responses SSE
 *    stream (named events from `response.created` to `response.completed`). Any other request is
 *    one JSON `Response`. `previous_response_id`, `store` and `conversation` are accepted and
 *    ignored: nothing is stored, and the call is the next step.
 *  - `POST /gemini/…:generateContent` — Gemini `generateContent`, as one JSON response.
 *    `POST /gemini/…:streamGenerateContent?alt=sse` — the same reply as an SSE stream of
 *    `GenerateContentResponse` data frames; without `alt=sse` it is the exception 400. The model
 *    id is the path segment between `/models/` and `:`.
 *  - The vendor routes share the scenario's steps: call N, on whichever route, gets step N.
 *    A step's `firstByteDelayMs` holds back the whole response, and its `streamInterChunkDelayMs`
 *    spaces out streamed deltas.
 *  - `GET /health` — liveness, the loaded scenario name, its step count and unused steps, and
 *    whether lenient mode is on. Always open, including when a control token is configured.
 *  - `POST /control/scenario` with exactly one of `{"name": "..."}` or `{"scenario": { ... }}` —
 *    load that scenario and start it over (empty call and exchange logs, next call is call 1). A name is resolved from the scenario
 *    directory (`--scenario-dir` or `FAKE_LLM_SCENARIO_DIR`), then the classpath `/scenarios/`, then
 *    the bundled scenarios.
 *  - `GET /control/scenarios` — `{"scenarios": [names]}` from the directory and the indexes.
 *  - `POST /control/reset` — start the loaded scenario over: empty call and exchange logs, next call
 *    is call 1.
 *  - `POST /control/unload` — remove the loaded scenario: empty call and exchange logs, next call
 *    is call 1, and calls get the no-scenario 400 (or the canned reply when lenient).
 *  - `GET /control/calls` — the call log.
 *  - `GET /control/exchanges` — each call's request as received (auth header values and a `key`
 *    query parameter redacted) and response as written, in call order.
 *
 * `fake-llm validate <file|dir>...` checks scenario files instead of starting a server (see
 * [validateScenarios]).
 *
 * Startup flags override the matching environment variable when both are set: `--port <n>` (`PORT`,
 * default 8080), `--scenario <name>` (`FAKE_LLM_SCENARIO`), `--scenario-dir <path>`
 * (`FAKE_LLM_SCENARIO_DIR`). `--lenient` (no value) turns lenient mode on; without it,
 * `FAKE_LLM_LENIENT=true` or `false` (any case) decides, default false. An unknown flag, a port
 * outside 0–65535, a scenario directory that is not a valid path, or any other `FAKE_LLM_LENIENT`
 * value prints a short usage line and exits.
 *
 * **Control auth.** When [readControlToken] resolves a token, every path under `/control/` requires
 * `Authorization: Bearer <token>`. The check is a path prefix, so `/health` stays open. With no
 * token configured, the control routes are open.
 *
 * [createFakeLlmHttpServer] binds the IPv6 wildcard `::` when the host has an IPv6 stack, which
 * covers an IPv6-only host and a dual-stack host. A host with no IPv6 stack cannot bind `::`, and
 * the server falls back to the IPv4 wildcard `0.0.0.0`. The choice is which address family the
 * host can bind.
 */
public fun main(args: Array<String>) {
    if (args.firstOrNull() == "validate") {
        val code = validateScenarios(args.drop(1), System.out, System.err)
        if (code != 0) exitProcess(code)
        return
    }
    val config =
        try {
            parseStartupConfig(args)
        } catch (e: StartupUsageException) {
            System.err.println(e.message)
            exitProcess(1)
        }
    val engine = config.engine()
    config.scenario?.let { engine.load(it) }
    val server = startFakeLlmServer(config.port, engine)
    println("fake-llm listening on :${server.port} (scenario=${engine.currentScenarioName() ?: "none"})")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}

private const val DEFAULT_PORT = "8080"
private const val MAX_PORT = 65535

internal class StartupUsageException(
    message: String,
) : RuntimeException(message)

internal data class StartupConfig(
    val port: Int,
    val scenario: String?,
    val scenarioDir: Path?,
    val lenient: Boolean,
) {
    /** The engine [main] serves: this lenient mode and scenario directory. */
    fun engine(): ScenarioEngine = ScenarioEngine(lenient = lenient, scenarioDirectory = scenarioDir)

    companion object {
        /**
         * A path the platform cannot represent (a NUL character anywhere, or for example `foo*` on
         * Windows) is a usage error.
         */
        fun parseScenarioDir(raw: String?): Path? {
            if (raw == null) return null
            return try {
                Path.of(raw)
            } catch (e: InvalidPathException) {
                usage("invalid scenario directory: ${e.reason}")
            }
        }
    }
}

/**
 * Flags win over [env]. An unknown flag or a bad value throws [StartupUsageException].
 * [env] defaults to the process environment. A blank environment variable is ignored.
 * `--lenient` takes no value and turns lenient mode on. `FAKE_LLM_LENIENT` accepts `true` or `false` in any case.
 */
internal fun parseStartupConfig(
    args: Array<String>,
    env: Map<String, String> = System.getenv(),
): StartupConfig {
    var portFlag: String? = null
    var scenarioFlag: String? = null
    var scenarioDirFlag: String? = null
    var lenientFlag = false
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when (arg) {
            "--port" -> {
                portFlag = optionValue(args, i, arg)
                i += 2
            }

            "--scenario" -> {
                scenarioFlag = optionValue(args, i, arg)
                i += 2
            }

            "--scenario-dir" -> {
                scenarioDirFlag = optionValue(args, i, arg)
                i += 2
            }

            "--lenient" -> {
                lenientFlag = true
                i += 1
            }

            else -> {
                usage(if (arg.startsWith("-")) "unknown flag '$arg'" else "unknown argument '$arg'")
            }
        }
    }
    // A present flag overrides the environment variable for the same setting.
    val portRaw = portFlag ?: env["PORT"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PORT
    val port = portRaw.toIntOrNull()?.takeIf { it in 0..MAX_PORT } ?: usage("invalid port '$portRaw'")
    val scenario = scenarioFlag ?: env["FAKE_LLM_SCENARIO"]?.takeIf { it.isNotBlank() }
    val scenarioDirRaw = scenarioDirFlag ?: env["FAKE_LLM_SCENARIO_DIR"]?.takeIf { it.isNotBlank() }
    val lenient = lenientFlag || parseLenientEnv(env["FAKE_LLM_LENIENT"])
    return StartupConfig(
        port = port,
        scenario = scenario,
        scenarioDir = StartupConfig.parseScenarioDir(scenarioDirRaw),
        lenient = lenient,
    )
}

/** Blank or absent [envValue] is false. Anything other than true or false (any case) is a usage error. */
private fun parseLenientEnv(envValue: String?): Boolean {
    val raw = envValue?.takeIf { it.isNotBlank() } ?: return false
    return when (raw.lowercase()) {
        "true" -> true
        "false" -> false
        else -> usage("invalid FAKE_LLM_LENIENT value '$raw' (expected true or false)")
    }
}

private fun optionValue(
    args: Array<String>,
    flagIndex: Int,
    flag: String,
): String {
    val value = args.getOrNull(flagIndex + 1)
    if (value.isNullOrBlank() || value.startsWith("-")) usage("missing value for $flag")
    return value
}

private fun usage(detail: String): Nothing =
    throw StartupUsageException(
        "fake-llm: $detail\nUsage: fake-llm [--port <n>] [--scenario <name>] [--scenario-dir <path>] [--lenient]\n" +
            "       fake-llm validate <file|dir>...",
    )
