package com.davidjonesgriz.fakellm.conformance

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Starting and stopping servers by command, and planning runs for both targets. */
class ServersTest {
    private val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    private val stub = "'$java' -cp '${System.getProperty("fakellm.stubClasspath")}' com.davidjonesgriz.fakellm.conformance.StubServer"

    private fun case(
        name: String,
        server: ServerSettings = ServerSettings(),
    ) = Case(name, "d", server, JsonPrimitive("hello"), emptyList(), Path.of("$name.json"), "$name.json")

    private fun pidOf(output: String): Long = Regex("pid (\\d+)").find(output)!!.groupValues[1].toLong()

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test
    fun `a command server gets its port and settings from the environment, and stops`() {
        val server = CommandServer.start(stub, ServerSettings(lenient = true, controlToken = "t"))
        val pid = pidOf(server.output())
        server.use {
            val env = Json.parseToJsonElement(CaseRunner(it.baseUrl).send(CaseRequest("GET", "/env")).body).jsonObject
            assertEquals("true", env.getValue("FAKE_LLM_LENIENT").jsonPrimitive.content)
            assertEquals("t", env.getValue("FAKE_LLM_CONTROL_TOKEN").jsonPrimitive.content)
            assertEquals("", env.getValue("FAKE_LLM_SCENARIO").jsonPrimitive.content)
            assertEquals("", env.getValue("FAKE_LLM_SCENARIO_DIR").jsonPrimitive.content)
        }
        assertFalse(isAlive(pid))
    }

    @Test
    fun `the inherited environment cannot change the settings`() {
        // The Gradle build runs these tests with FAKE_LLM_LENIENT=true and FAKE_LLM_INHERITED set.
        assertEquals("true", System.getenv("FAKE_LLM_LENIENT"))
        CommandServer.start(stub, ServerSettings()).use {
            val env = Json.parseToJsonElement(CaseRunner(it.baseUrl).send(CaseRequest("GET", "/env")).body).jsonObject
            assertEquals("false", env.getValue("FAKE_LLM_LENIENT").jsonPrimitive.content)
            assertEquals("", env.getValue("FAKE_LLM_CONTROL_TOKEN").jsonPrimitive.content)
            assertFalse("FAKE_LLM_INHERITED" in env)
        }
    }

    @Test
    fun `a command that exits fails at once, with what it printed`() {
        val started = System.nanoTime()
        val failure = assertFailsWith<ServerStartException> { CommandServer.start("STUB_MODE=exit $stub", ServerSettings()) }
        assertContains(failure.message!!, "exited with code 3")
        assertContains(failure.message!!, "stub exiting on purpose")
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(20))
    }

    @Test
    fun `a command that never answers times out and is stopped`() {
        val failure =
            assertFailsWith<ServerStartException> {
                CommandServer.start("STUB_MODE=hang $stub", ServerSettings(), startupTimeout = Duration.ofSeconds(2))
            }
        assertContains(failure.message!!, "no answer from GET")
        assertFalse(isAlive(pidOf(failure.message!!)))
    }

    @Test
    fun `a server that ignores the polite stop is killed`() {
        val server = CommandServer.start("STUB_MODE=block-shutdown $stub", ServerSettings())
        val pid = pidOf(server.output())
        server.close()
        assertFalse(isAlive(pid))
    }

    @Test
    fun `a server the command started in the background is stopped too`() {
        val server = CommandServer.start("$stub & wait", ServerSettings())
        val pid = pidOf(server.output())
        server.close()
        assertFalse(isAlive(pid))
    }

    @Test
    fun `with a command target, equal settings share a server and fresh cases get their own`() {
        val cases =
            listOf(
                case("a"),
                case("b", ServerSettings(lenient = true)),
                case("c"),
                case("d", ServerSettings(fresh = true)),
                case("e", ServerSettings(controlToken = "t")),
            )
        val plan = planRun(cases, Target.Command("true"))
        assertEquals(
            listOf(listOf("a", "c"), listOf("b"), listOf("e"), listOf("d")),
            plan.groups.map { group -> group.cases.map { it.name } },
        )
        assertEquals(
            listOf(
                "server: strict, no control token",
                "server: lenient, no control token",
                "server: strict, control token",
                "fresh server: d",
            ),
            plan.groups.map { it.label },
        )
        assertTrue(plan.skipped.isEmpty())
    }

    /** A running server that reports [lenient] and, when [token] is set, requires it on /control/. */
    private fun withRunning(
        lenient: Boolean,
        token: String?,
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val authorized = token == null || ex.requestHeaders.getFirst("Authorization") == "Bearer $token"
            val (status, body) =
                when {
                    path == "/health" -> 200 to """{"status":"ok","scenario":null,"lenient":$lenient}"""
                    path.startsWith("/control/") && !authorized -> 401 to """{"fake_llm_error":"unauthorized"}"""
                    else -> 200 to """{"scenarios":[]}"""
                }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `with a base URL target, cases the server cannot run are skipped with the reason`() {
        val cases =
            listOf(
                case("plain"),
                case("fresh", ServerSettings(fresh = true)),
                case("lenient", ServerSettings(lenient = true)),
                case("token", ServerSettings(controlToken = "t")),
            )
        withRunning(lenient = false, token = null) { url ->
            val plan = planRun(cases, Target.BaseUrl(url, null))
            assertEquals(
                listOf("plain"),
                plan.groups
                    .single()
                    .cases
                    .map { it.name },
            )
            assertEquals(
                url,
                plan.groups
                    .single()
                    .server()
                    .baseUrl,
            )
            val reasons = plan.skipped.associate { (case, reason) -> case.name to reason }
            assertContains(reasons.getValue("fresh"), "newly started server")
            assertContains(reasons.getValue("lenient"), "needs lenient=true, and the server reports lenient=false")
            assertContains(reasons.getValue("token"), "needs the control token 't', and the target's is none")
        }
        withRunning(lenient = true, token = "t") { url ->
            val plan = planRun(cases, Target.BaseUrl(url, "t"))
            assertEquals(emptyList(), plan.groups)
            assertContains(plan.skipped.toMap().getValue(cases[0]), "needs lenient=false")
            assertContains(plan.skipped.toMap().getValue(cases[3]), "needs lenient=false")
        }
        withRunning(lenient = false, token = "t") { url ->
            val plan = planRun(cases, Target.BaseUrl(url, "t"))
            assertEquals(
                listOf("token"),
                plan.groups
                    .single()
                    .cases
                    .map { it.name },
            )
            assertContains(plan.skipped.toMap().getValue(cases[0]), "needs a server without a control token")
        }
    }

    @Test
    fun `a base URL target's control token is checked once, against the server`() {
        val cases = listOf(case("plain"))
        withRunning(lenient = false, token = "t") { url ->
            assertEquals(
                "the server requires a control token; set fakellm.controlToken",
                planRun(cases, Target.BaseUrl(url, null)).targetProblem,
            )
            assertEquals("the server rejects fakellm.controlToken", planRun(cases, Target.BaseUrl(url, "wrong")).targetProblem)
        }
        withRunning(lenient = false, token = null) { url ->
            val plan = planRun(cases, Target.BaseUrl(url, "t"))
            assertEquals("fakellm.controlToken is set, but the server does not require a control token", plan.targetProblem)
            assertEquals(cases, plan.skipped.map { it.first })
        }
        assertContains(planRun(cases, Target.BaseUrl("http://127.0.0.1:1", null)).targetProblem!!, "cannot reach")
    }

    @Test
    fun `the suite skips without a target, and fails instead with failOnSkip`() {
        val properties = listOf("fakellm.command", "fakellm.baseUrl", "fakellm.failOnSkip")
        val saved = properties.associateWith { System.getProperty(it) }
        try {
            properties.forEach { System.clearProperty(it) }
            val skipped = ConformanceSuite().cases().toList().single() as DynamicTest
            assertFailsWith<TestAbortedException> { skipped.executable.execute() }

            System.setProperty("fakellm.failOnSkip", "true")
            val failing = ConformanceSuite().cases().toList().single() as DynamicTest
            assertContains(assertFailsWith<AssertionFailedError> { failing.executable.execute() }.message!!, "fakellm.failOnSkip")
        } finally {
            saved.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
    }

    @Test
    fun `a group whose server cannot start fails each of its cases with the reason`() {
        val properties = listOf("fakellm.command", "fakellm.baseUrl", "fakellm.failOnSkip")
        val saved = properties.associateWith { System.getProperty(it) }
        try {
            properties.forEach { System.clearProperty(it) }
            System.setProperty("fakellm.command", "STUB_MODE=exit $stub")
            val group = ConformanceSuite().cases().toList().first() as DynamicContainer
            val test = group.children.toList().first() as DynamicTest
            assertContains(assertFailsWith<ServerStartException> { test.executable.execute() }.message!!, "exited with code 3")
        } finally {
            saved.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
    }
}
