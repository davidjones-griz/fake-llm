package com.davidjonesgriz.fakellm.conformance

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

/** A server the cases run against. [close] stops it when the runner started it. */
interface ServerHandle : AutoCloseable {
    val baseUrl: String

    /** What the server printed, when the runner started it; shown in a failing case's report. */
    fun output(): String = ""

    override fun close() = Unit
}

/** The startup failed: the process exited or never answered `GET /health`. */
class ServerStartException(
    message: String,
) : RuntimeException(message)

/**
 * A server the runner starts with `sh -c <command>` on a free port. The settings reach it through
 * the environment: `PORT`, `FAKE_LLM_LENIENT` (`true` or `false`) and `FAKE_LLM_CONTROL_TOKEN` (empty
 * for none). `FAKE_LLM_SCENARIO` and `FAKE_LLM_SCENARIO_DIR` are set empty and any other inherited
 * `FAKE_LLM_*` variable is removed, so the inherited environment cannot change the settings.
 * stdout and stderr go to a temporary file, not a pipe that could fill up and stall the server.
 */
class CommandServer private constructor(
    private val process: Process,
    private val outputFile: Path,
    override val baseUrl: String,
) : ServerHandle {
    private val hook = Thread { stop() }

    init {
        Runtime.getRuntime().addShutdownHook(hook)
    }

    override fun output(): String = runCatching { outputFile.readText() }.getOrDefault("")

    override fun close() {
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        stop()
        outputFile.deleteIfExists()
    }

    /**
     * Stops the process and everything it started. The descendants are listed first: once `sh`
     * exits, its children are reparented and no longer show up as its descendants. SIGTERM first,
     * then SIGKILL for whatever is still alive after [STOP_GRACE].
     */
    private fun stop() {
        val all = process.descendants().toList() + process.toHandle()
        all.forEach { it.destroy() }
        val deadline = System.nanoTime() + STOP_GRACE.toNanos()
        all.forEach { handle ->
            val left = deadline - System.nanoTime()
            if (left > 0) runCatching { handle.onExit().get(left, TimeUnit.NANOSECONDS) }
        }
        val left = all.filter { it.isAlive }
        left.forEach { it.destroyForcibly() }
        left.forEach { runCatching { it.onExit().get(STOP_GRACE.toNanos(), TimeUnit.NANOSECONDS) } }
    }

    companion object {
        private val STOP_GRACE: Duration = Duration.ofSeconds(5)
        private val POLL_INTERVAL: Duration = Duration.ofMillis(100)
        private val HEALTH_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(1)

        /**
         * Starts [command] with [settings] and waits until `GET /health` answers 200. A process that
         * exits first, or no answer within [startupTimeout], throws [ServerStartException] with
         * what the process printed; the process is stopped either way.
         */
        fun start(
            command: String,
            settings: ServerSettings,
            startupTimeout: Duration = DEFAULT_STARTUP_TIMEOUT,
        ): CommandServer {
            val port = freePort()
            val output = Files.createTempFile("fake-llm-conformance-", ".log")
            val builder = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).redirectOutput(output.toFile())
            builder.environment().apply {
                keys.removeIf { it.startsWith("FAKE_LLM_") }
                put("PORT", port.toString())
                put("FAKE_LLM_LENIENT", settings.lenient.toString())
                put("FAKE_LLM_CONTROL_TOKEN", settings.controlToken ?: "")
                put("FAKE_LLM_SCENARIO", "")
                put("FAKE_LLM_SCENARIO_DIR", "")
            }
            val server = CommandServer(builder.start(), output, "http://127.0.0.1:$port")
            try {
                server.awaitHealthy(startupTimeout)
            } catch (e: ServerStartException) {
                server.close()
                throw e
            }
            return server
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }

    /** Polls `GET /health`. Any I/O error counts as not ready yet: Docker accepts connections early. */
    private fun awaitHealthy(timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(HEALTH_REQUEST_TIMEOUT)
            .build()
            .use { http -> pollHealth(http, deadline, timeout) }
    }

    private fun pollHealth(
        http: HttpClient,
        deadline: Long,
        timeout: Duration,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUrl/health"))
                .timeout(HEALTH_REQUEST_TIMEOUT)
                .GET()
                .build()
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw ServerStartException("the server exited with code ${process.exitValue()} before answering GET /health:\n${output()}")
            }
            val status =
                try {
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                } catch (_: IOException) {
                    null
                }
            if (status == HTTP_OK) return
            Thread.sleep(POLL_INTERVAL.toMillis())
        }
        throw ServerStartException("no answer from GET $baseUrl/health within ${timeout.seconds} s:\n${output()}")
    }
}

/** A server already running at [baseUrl]; the runner neither starts nor stops it. */
class RunningServer(
    override val baseUrl: String,
) : ServerHandle

/** How to reach the server under test. */
sealed interface Target {
    /** The runner starts a server with [command] for each group of cases. */
    data class Command(
        val command: String,
    ) : Target

    /** One server already running at [baseUrl], with [controlToken] if it has one. */
    data class BaseUrl(
        val baseUrl: String,
        val controlToken: String?,
    ) : Target
}

/** Cases that share one server, started by [server] when the group runs. */
class CaseGroup(
    val label: String,
    val cases: List<Case>,
    val server: () -> ServerHandle,
)

/**
 * What to run: the [groups], the cases [skipped] with their reason, and a [targetProblem] that
 * makes every case skip (for example a control token that does not match the server's).
 */
class RunPlan(
    val groups: List<CaseGroup>,
    val skipped: List<Pair<Case, String>>,
    val targetProblem: String? = null,
)

/**
 * Plans a run. With a command target, cases with the same settings share a server and each
 * `fresh` case gets its own. With a base URL target, every case runs on that server except those
 * it cannot: `fresh` cases, and cases whose lenient mode or control token differ from the server's.
 */
fun planRun(
    cases: List<Case>,
    target: Target,
    startupTimeout: Duration = DEFAULT_STARTUP_TIMEOUT,
): RunPlan =
    when (target) {
        is Target.Command -> {
            val (fresh, shared) = cases.partition { it.server.fresh }
            val groups =
                shared.groupBy { it.server.shared }.map { (settings, group) ->
                    CaseGroup("server: $settings", group) { CommandServer.start(target.command, settings, startupTimeout) }
                } +
                    fresh.map { case ->
                        CaseGroup("fresh server: ${case.name}", listOf(case)) {
                            CommandServer.start(target.command, case.server, startupTimeout)
                        }
                    }
            RunPlan(groups, skipped = emptyList())
        }

        is Target.BaseUrl -> {
            planBaseUrl(cases, target)
        }
    }

private fun planBaseUrl(
    cases: List<Case>,
    target: Target.BaseUrl,
): RunPlan {
    val probe = TargetProbe(target)
    probe.problem?.let { problem -> return RunPlan(emptyList(), cases.map { it to problem }, targetProblem = problem) }
    val (runnable, skipped) =
        cases.map { it to skipReason(it, probe.lenient, target.controlToken) }.partition { it.second == null }
    val groups =
        if (runnable.isEmpty()) {
            emptyList()
        } else {
            listOf(
                CaseGroup(
                    "server: ${target.baseUrl}",
                    runnable.map {
                        it.first
                    },
                ) { RunningServer(target.baseUrl) },
            )
        }
    return RunPlan(groups, skipped.map { (case, reason) -> case to reason!! })
}

private fun skipReason(
    case: Case,
    serverLenient: Boolean,
    serverToken: String?,
): String? =
    when {
        case.server.fresh -> {
            "needs a newly started server, and the target is an already running one"
        }

        case.server.lenient != serverLenient -> {
            "needs lenient=${case.server.lenient}, and the server reports lenient=$serverLenient"
        }

        case.server.controlToken != serverToken -> {
            if (case.server.controlToken == null) {
                "needs a server without a control token"
            } else {
                "needs the control token '${case.server.controlToken}', and the target's is ${serverToken?.let { "'$it'" } ?: "none"}"
            }
        }

        else -> {
            null
        }
    }

/** Reads the running server's lenient mode and checks the target's control token against it. */
private class TargetProbe(
    target: Target.BaseUrl,
) {
    var lenient = false
    var problem: String? = null

    init {
        try {
            CaseRunner(target.baseUrl).use { probe(it, target.controlToken) }
        } catch (e: CaseFailedException) {
            problem = "cannot reach ${target.baseUrl}: ${e.message}"
        } catch (e: SerializationException) {
            problem = "GET ${target.baseUrl}/health did not return JSON: ${e.message}"
        } catch (e: IllegalArgumentException) {
            problem = "GET ${target.baseUrl}/health did not return an object: ${e.message}"
        }
    }

    private fun probe(
        runner: CaseRunner,
        token: String?,
    ) {
        val health = Json.parseToJsonElement(runner.send(CaseRequest("GET", "/health")).body).jsonObject
        val reported = health["lenient"]?.jsonPrimitive?.booleanOrNull
        if (reported == null) {
            problem = "GET /health has no boolean lenient field"
            return
        }
        lenient = reported
        val open = runner.send(CaseRequest("GET", "/control/scenarios")).status
        problem =
            when {
                token == null && open == HTTP_UNAUTHORIZED -> {
                    "the server requires a control token; set fakellm.controlToken"
                }

                token == null -> {
                    null
                }

                open != HTTP_UNAUTHORIZED -> {
                    "fakellm.controlToken is set, but the server does not require a control token"
                }

                runner.send(CaseRequest("GET", "/control/scenarios", mapOf("authorization" to "Bearer $token"))).status != HTTP_OK -> {
                    "the server rejects fakellm.controlToken"
                }

                else -> {
                    null
                }
            }
    }
}

/** How long a started server has to answer `GET /health`. */
val DEFAULT_STARTUP_TIMEOUT: Duration = Duration.ofSeconds(STARTUP_TIMEOUT_SECONDS)

private const val STARTUP_TIMEOUT_SECONDS = 30L

private const val HTTP_OK = 200
private const val HTTP_UNAUTHORIZED = 401
