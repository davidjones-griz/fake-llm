// A stand-in server for CommandServerTest, started in its own JVM the way a real server command is.
@file:JvmName("StubServer")

package com.davidjonesgriz.fakellm.conformance

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Reads `PORT` and the `FAKE_LLM_*` settings like a real server and answers `GET /health` and
 * `GET /env` (the settings it received, as JSON). `STUB_MODE` changes its behaviour:
 * `exit` exits at once with code 3, `hang` never listens, and `block-shutdown` ignores the polite
 * stop by blocking in a shutdown hook. It prints its process ID first, so a test can check it is gone.
 */
fun main() {
    println("pid ${ProcessHandle.current().pid()}")
    System.out.flush()
    when (System.getenv("STUB_MODE")) {
        "exit" -> {
            println("stub exiting on purpose")
            kotlin.system.exitProcess(3)
        }

        "hang" -> {
            Thread.sleep(Long.MAX_VALUE)
        }

        "block-shutdown" -> {
            Runtime.getRuntime().addShutdownHook(Thread { Thread.sleep(Long.MAX_VALUE) })
        }
    }
    val settings = System.getenv().filterKeys { it.startsWith("FAKE_LLM_") }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", System.getenv("PORT").toInt()), 0)
    server.createContext("/health") { ex ->
        val body = """{"status":"ok","scenario":null,"lenient":${System.getenv("FAKE_LLM_LENIENT") == "true"}}""".toByteArray()
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    server.createContext("/env") { ex ->
        val body = settings.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }.toByteArray()
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    server.start()
}
