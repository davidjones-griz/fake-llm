package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Command-line entry point, used by `java -jar` and the Docker image. The JVM requires it to be
 * public, so it is listed in `api/fake-llm.api`, but it is not meant for library use: code that
 * embeds the server calls [startFakeLlmServer] instead.
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
 *  - `GET /health` — liveness, the loaded scenario name, and whether lenient mode is on. Always
 *    open, including when a control token is configured.
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

/**
 * Starts a server in this process, serving [engine]. Pass `port = 0` for an ephemeral port and
 * read it back from [FakeLlmServer.port]. When [controlToken] is non-null, every `/control/` route
 * requires `Authorization: Bearer <token>`; null leaves them open. The default reads the
 * `FAKE_LLM_CONTROL_TOKEN` environment variable, or the system property of the same name when the
 * variable is absent, so pass `null` explicitly for a server that ignores the environment.
 */
public fun startFakeLlmServer(
    port: Int,
    engine: ScenarioEngine,
    controlToken: String? = readControlToken(),
): FakeLlmServer {
    val server = createFakeLlmHttpServer(port)
    val executor = Executors.newFixedThreadPool(HTTP_THREADS)
    server.createContext("/") { ex -> route(ex, engine, controlToken) }
    server.executor = executor
    server.start()
    return FakeLlmServer(server, executor, engine)
}

/**
 * A running server from [startFakeLlmServer]. Load scenarios and read the call log through
 * [engine], or over HTTP at [baseUrl]. [close] stops the listener and its handler threads.
 */
public class FakeLlmServer internal constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    public val engine: ScenarioEngine,
) : AutoCloseable {
    /** The bound port. With `port = 0` this is the ephemeral port the OS chose. */
    public val port: Int = server.address.port

    /**
     * `http://127.0.0.1:<port>`. Vendor SDK base URLs append `/anthropic`, `/openai/v1`, or
     * `/gemini/`.
     */
    public val baseUrl: String = "http://127.0.0.1:$port"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

internal fun handleMessages(
    ex: HttpExchange,
    engine: ScenarioEngine,
) {
    val body = ex.requestBody.readBytes().decodeToString()
    val parsed = parseAnthropicRequest(body)
    if (parsed.stream) {
        streamAnthropicMessages(ex, engine, parsed)
    } else {
        respondFake(ex, engine.handle(vendor = "anthropic", req = parsed, capture = ex.exchangeCapture()))
    }
}

internal fun handleOpenAi(
    ex: HttpExchange,
    engine: ScenarioEngine,
) {
    val body = ex.requestBody.readBytes().decodeToString()
    val parsed = parseOpenAiRequest(body)
    if (parsed.stream) {
        streamOpenAiChatCompletion(ex, engine, parsed)
    } else {
        respondFake(ex, engine.handle(vendor = "openai", req = parsed, capture = ex.exchangeCapture()))
    }
}

internal fun handleGemini(
    ex: HttpExchange,
    engine: ScenarioEngine,
    path: String,
) {
    val body = ex.requestBody.readBytes().decodeToString()
    val streamed = path.endsWith(":streamGenerateContent")
    require(!streamed || geminiAltSse(ex.requestURI.rawQuery)) { GEMINI_STREAM_NEEDS_ALT_SSE }
    val parsed = parseGeminiRequest(body, geminiModelFromPath(path), stream = streamed)
    if (streamed) {
        streamGeminiGenerateContent(ex, engine, parsed)
    } else {
        respondFake(ex, engine.handle(vendor = "gemini", req = parsed, capture = ex.exchangeCapture()))
    }
}

/**
 * SSE variant of [handleMessages]. Takes the call's number and step through the same
 * [ScenarioEngine.resolve] that [ScenarioEngine.handle] uses, so the call log records the request
 * once whichever transport answers it.
 *
 * A reply step is written as Anthropic's SSE sequence: the thinking block, the text block, then
 * one `tool_use` block per tool call, whose argument text is sent as `input_json_delta` pieces
 * (`rawArguments` verbatim), with `stop_reason` `tool_use` when there are tool calls. An error step whose
 * [Step.streamErrorAfterChunks] is null is a plain HTTP error, and no SSE bytes are written
 * first: that is how a provider rejects before generation starts (the usual timing for rate-limit
 * and overload errors). Setting [Step.streamErrorAfterChunks] opts into the other timing — some
 * text deltas, then an in-stream `event: error`. The "no more steps" and "no scenario loaded" 400s
 * are that same plain HTTP error: [ScenarioEngine.resolve] returns them as error steps with
 * [Step.streamErrorAfterChunks] unset, and the branch below returns before any SSE bytes.
 * [Step.firstByteDelayMs] is waited out first, before any bytes.
 */
private fun streamAnthropicMessages(
    ex: HttpExchange,
    engine: ScenarioEngine,
    req: ParsedRequest,
) {
    val resolution = engine.resolve(vendor = "anthropic", req = req, capture = ex.exchangeCapture())
    val step = resolution.step
    pauseBeforeFirstByte(step)
    val error = step.error
    if (error != null && step.streamErrorAfterChunks == null) {
        respond(ex, error.status, error.body, error.headers)
        return
    }
    val out = startEventStream(ex)
    try {
        writeSse(out, "message_start", messageStartJson(resolution.seq, req.model, req.hasCacheBreakpoint))
        if (error != null) {
            writeSse(out, "content_block_start", contentBlockStartJson())
            val prefix = errorPrefixChunks(step.streamErrorAfterChunks!!, step.streamChunkChars)
            emitDeltas(out, prefix, step.streamInterChunkDelayMs)
            writeSse(out, "error", error.body.ifBlank { GENERIC_STREAM_ERROR_BODY })
        } else {
            var index = 0
            step.thinking?.let { thinking ->
                writeSse(out, "content_block_start", thinkingBlockStartJson(index))
                emitThinkingDeltas(out, chunkText(thinking, step.streamChunkChars), step.streamInterChunkDelayMs, index)
                writeSse(out, "content_block_delta", signatureDeltaJson(FAKE_THINKING_SIGNATURE, index))
                writeSse(out, "content_block_stop", contentBlockStopJson(index++))
            }
            step.text?.let { text ->
                writeSse(out, "content_block_start", contentBlockStartJson(index))
                emitDeltas(out, chunkText(text, step.streamChunkChars), step.streamInterChunkDelayMs, index)
                writeSse(out, "content_block_stop", contentBlockStopJson(index++))
            }
            sentToolCalls("anthropic", step, resolution.seq).forEach { call ->
                writeSse(out, "content_block_start", toolUseBlockStartJson(index, call.id!!, call.name))
                emitInputJsonDeltas(out, chunkText(call.argumentText, step.streamChunkChars), step.streamInterChunkDelayMs, index)
                writeSse(out, "content_block_stop", contentBlockStopJson(index++))
            }
            writeSse(out, "message_delta", messageDeltaJson(if (step.toolCalls == null) step.stopReason else "tool_use"))
            writeSse(out, "message_stop", messageStopJson())
        }
    } finally {
        out.close()
    }
}

private const val DEFAULT_PORT = "8080"
private const val HTTP_THREADS = 16
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
