@file:JvmName("FakeLlm")

package com.davidjonesgriz.fakellm

import com.sun.net.httpserver.HttpServer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Starts a server in this process, serving [engine]. Pass `port = 0` for an ephemeral port and
 * read it back from [FakeLlmServer.port]. When [controlToken] is non-null, every `/control/` route
 * requires `Authorization: Bearer <token>`; null leaves them open. The default reads the
 * `FAKE_LLM_CONTROL_TOKEN` environment variable, or the system property of the same name when the
 * variable is absent, so pass `null` explicitly for a server that ignores the environment.
 *
 * From Java: `FakeLlm.startFakeLlmServer(0, engine)`, or `FakeLlm.startFakeLlmServer(0, engine, token)`
 * with a token or `null`.
 */
@JvmOverloads
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

private const val HTTP_THREADS = 16
