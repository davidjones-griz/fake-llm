package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `metadata` on a scenario and its steps: what it accepts, what it reports, and that it changes nothing else. */
class MetadataTest {
    private fun decode(text: String) = ScenarioLoader.decode(Json.parseToJsonElement(text))

    private fun messageFor(text: String) = assertFailsWith<IllegalArgumentException> { decode(text) }.message!!

    private fun user(text: String) = parseAnthropicRequest(anthropicBody(text))

    /** [element] with every `metadata` key removed, at any depth. */
    private fun withoutMetadata(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.filterKeys { it != "metadata" }.mapValues { withoutMetadata(it.value) })
            is JsonArray -> JsonArray(element.map(::withoutMetadata))
            else -> element
        }

    private val annotated =
        """{"metadata":{"name":"Note summary","description":"why","tags":["smoke"]},"steps":[
          {"metadata":{"name":"first","tags":["happy-path"]},"text":"one"},
          {"metadata":{"name":"limited","description":"a 429 first"},"error":{"status":429,"body":"slow down"}},
          {"metadata":{"name":"group","description":"either order","tags":["race"]},"parallel":{"steps":[
            {"metadata":{"name":"side-a"},"match":{"lastUser":{"contains":"a"}},"text":"A"},
            {"metadata":{"name":"side-b"},"text":"B"}]}},
          {"metadata":{"name":"poll"},"times":{"min":1,"max":2},"text":"again"}]}"""

    @Test
    fun `metadata is read onto the scenario and every kind of step, and kept on the model`() {
        val s = decode(annotated)
        assertEquals(ScenarioMetadata(name = "Note summary", description = "why", tags = listOf("smoke")), s.metadata)
        assertEquals(listOf("first", "limited", "group", "poll"), s.steps.map { it.metadata?.name })
        assertEquals(
            listOf("side-a", "side-b"),
            s.steps[2]
                .parallel!!
                .steps
                .map { it.metadata?.name },
        )
        assertEquals(listOf("race"), s.steps[2].metadata?.tags)
        assertEquals(emptyList(), s.steps[3].metadata!!.tags, "tags default to none")
    }

    @Test
    fun `metadata changes nothing about matching, replies or order`() {
        // The scenario's own metadata.name stays: it is an inline load's key, which the no-more-steps body names.
        val plain = ScenarioLoader.decode(withoutMetadata(Json.parseToJsonElement(annotated))).copy(metadata = decode(annotated).metadata)
        assertEquals(listOf(null, null, null, null), plain.steps.map { it.metadata })
        val calls = listOf("x", "x", "b", "a", "x", "x", "x")

        fun run(s: Scenario): Pair<List<String>, List<CallRecord>> {
            val engine = ScenarioEngine().also { it.loadInline(s) }
            val bodies =
                calls.map { text ->
                    val r = engine.handle("anthropic", user(text))
                    "${r.status} ${r.body.replace(Regex("\"fake_llm_seq\":\\d+"), "")}"
                }
            return bodies to engine.calls()
        }
        val (withBodies, withCalls) = run(decode(annotated))
        val (plainBodies, plainCalls) = run(plain)
        assertEquals(plainBodies, withBodies)
        assertEquals(
            plainCalls.map { it.copy(timestampMs = 0) },
            withCalls.map { it.copy(timestampMs = 0, stepName = null) },
            "the call log differs only by stepName",
        )
        assertEquals(listOf("first", "limited", "side-b", "side-a", "poll", "poll", null), withCalls.map { it.stepName })
        assertEquals("no-more-steps", withCalls.last().outcome)
    }

    @Test
    fun `a test can find a call by its step name after a step is inserted before it`() {
        fun scenario(vararg extra: Step) =
            Scenario(
                steps =
                    listOf(
                        Step(text = "classify", metadata = ScenarioMetadata(name = "classify")),
                        *extra,
                        Step(text = "summarize", metadata = ScenarioMetadata(name = "summarize")),
                    ),
            )

        fun summarizeCall(s: Scenario): CallRecord {
            val engine = ScenarioEngine().also { it.loadInline(s) }
            repeat(s.steps.size) { engine.handle("anthropic", user("go")) }
            return engine.calls().single { it.stepName == "summarize" }
        }
        val before = summarizeCall(scenario())
        val after = summarizeCall(scenario(Step(error = ErrorStep(status = 429))))
        assertEquals(2 to 3, before.step to after.step, "the position shifted")
        assertEquals("summarize", after.stepName)
    }

    @Test
    fun `an unnamed step has no stepName, and the call log and CLI line show a name only when set`() {
        val engine =
            ScenarioEngine().also {
                it.loadInline(
                    Scenario(steps = listOf(Step(text = "a"), Step(text = "b", metadata = ScenarioMetadata(name = "the \"b\" step")))),
                )
            }
        engine.handle("anthropic", user("x"))
        engine.handle("anthropic", user("x"))
        val (first, second) = engine.calls()
        assertNull(first.stepName)
        assertNull(Json.parseToJsonElement(logJson.encodeToString(CallRecord.serializer(), first)).jsonObject["stepName"])
        assertEquals("fake-llm: call 1 anthropic model=\"example-model\" outcome=text step=1", callLogLine(first))
        assertEquals(
            "fake-llm: call 2 anthropic model=\"example-model\" outcome=text step=2 name=\"the \\\"b\\\" step\"",
            callLogLine(second),
        )
    }

    @Test
    fun `unused steps are reported by number, with the names of those that have one`() {
        val engine = ScenarioEngine().also { it.loadInline(decode(annotated)) }
        assertEquals(listOf(1, 2, 3, 4), engine.unusedSteps)
        assertEquals(mapOf(1 to "first", 2 to "limited", 3 to "group", 4 to "poll"), engine.unusedStepNames)
        engine.handle("anthropic", user("x"))
        engine.handle("anthropic", user("x"))
        engine.handle("anthropic", user("a"))
        assertEquals(listOf(3, 4), engine.unusedSteps, "the group has one unused step")
        assertEquals(mapOf(3 to "group", 4 to "poll"), engine.unusedStepNames)
        val unnamed = ScenarioEngine().also { it.loadInline(Scenario(steps = listOf(Step(text = "a")))) }
        assertEquals(emptyMap(), unnamed.unusedStepNames)
    }

    @Test
    fun `a load by name is keyed by the file name, whatever metadata name says, and an inline load by its name or inline`() {
        withScenarioDir { dir ->
            writeScenario(dir, "named", """{"metadata":{"name":"Shown name"},"steps":[{"text":"a"}]}""")
            writeScenario(dir, "unnamed", """{"steps":[{"text":"a"}]}""")
            val engine = ScenarioEngine(scenarioDirectory = dir)
            engine.load("named")
            assertEquals("named" to "Shown name", engine.progress.key to engine.progress.displayName)
            engine.reset()
            assertEquals("named", engine.currentScenarioKey(), "reset keeps the key")
            engine.load("unnamed")
            assertEquals("unnamed" to "unnamed", engine.progress.key to engine.progress.displayName)
            engine.loadInline(Scenario(steps = listOf(Step(text = "a")), metadata = ScenarioMetadata(name = "Inline name")))
            assertEquals("Inline name" to "Inline name", engine.progress.key to engine.progress.displayName)
            engine.loadInline(Scenario(steps = listOf(Step(text = "a")), metadata = ScenarioMetadata(description = "no name")))
            assertEquals("inline" to "inline", engine.progress.key to engine.progress.displayName)
            engine.unload()
            assertEquals(null to null, engine.progress.key to engine.progress.displayName)
        }
    }

    @Test
    fun `health, the load response and the no-more-steps body report the key and the display name`() {
        withScenarioDir { dir ->
            writeScenario(dir, "shown", """{"metadata":{"name":"Shown name"},"steps":[{"metadata":{"name":"only"},"text":"a"}]}""")
            withServer(ScenarioEngine(scenarioDirectory = dir)) { client, _ ->
                val loaded = Json.parseToJsonElement(client.post("/control/scenario", """{"name":"shown"}""").body()).jsonObject
                assertEquals("""{"loaded":"shown","name":"Shown name","steps":1}""", loaded.toString())
                val health = Json.parseToJsonElement(client.get("/health").body()).jsonObject
                assertEquals("shown", health.getValue("scenario").jsonPrimitive.content)
                assertEquals("Shown name", health.getValue("scenarioName").jsonPrimitive.content)
                assertEquals("""{"1":"only"}""", health.getValue("unusedStepNames").toString())
                client.post("/anthropic/v1/messages", anthropicBody("a"))
                val calls = Json.parseToJsonElement(client.get("/control/calls").body()).jsonArray
                assertEquals(
                    "only",
                    calls
                        .single()
                        .jsonObject
                        .getValue("stepName")
                        .jsonPrimitive.content,
                )
                val after = client.post("/anthropic/v1/messages", anthropicBody("b")).body()
                assertContains(after, "of scenario 'shown', which has 1 step")
                assertContains(after, "\"scenario\":\"shown\"")
                val inline = client.post("/control/scenario", """{"scenario":{"steps":[{"text":"a"}]}}""").body()
                assertEquals("""{"loaded":"inline","name":"inline","steps":1}""", inline)
                assertEquals(
                    "inline",
                    Json
                        .parseToJsonElement(client.get("/health").body())
                        .jsonObject
                        .getValue("scenario")
                        .jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun `a top-level name or description is rejected with a pointer to metadata`() {
        assertEquals(
            "\$: unknown field 'name': a scenario's name is now metadata.name",
            messageFor("""{"name":"x","steps":[{"text":"t"}]}"""),
        )
        assertEquals(
            "\$: unknown field 'description': a scenario's description is now metadata.description",
            messageFor("""{"metadata":{"name":"x"},"description":"d","steps":[{"text":"t"}]}"""),
        )
    }

    @Test
    fun `only name, description and tags are allowed, and only on the scenario and on steps`() {
        assertEquals("\$.metadata: unknown field 'owner'", messageFor("""{"metadata":{"owner":"me"},"steps":[{"text":"t"}]}"""))
        assertEquals(
            "step 1: \$.steps[0].metadata: unknown field 'title'",
            messageFor("""{"steps":[{"text":"t","metadata":{"title":"x"}}]}"""),
        )
        assertEquals(
            "step 1.1: \$.steps[0].parallel.steps[0].metadata: unknown field 'nam'",
            messageFor("""{"steps":[{"parallel":{"steps":[{"text":"t","metadata":{"nam":"x"}}]}}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0].match: unknown field 'metadata'",
            messageFor("""{"steps":[{"text":"t","match":{"model":"m","metadata":{"name":"x"}}}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0].error: unknown field 'metadata'",
            messageFor("""{"steps":[{"error":{"status":500,"metadata":{"name":"x"}}}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0].toolCalls[0]: unknown field 'metadata'",
            messageFor("""{"steps":[{"toolCalls":[{"name":"f","metadata":{"name":"x"}}]}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0].parallel: unknown field 'metadata'",
            messageFor("""{"steps":[{"parallel":{"steps":[{"text":"a"}],"metadata":{"name":"x"}}}]}"""),
        )
        assertEquals("metadata.name must not be empty", messageFor("""{"metadata":{"name":""},"steps":[{"text":"t"}]}"""))
        assertEquals(
            "step 1: metadata.tags must not contain an empty tag",
            messageFor("""{"steps":[{"text":"t","metadata":{"tags":[""]}}]}"""),
        )
        assertEquals(
            "step 1: \$.steps[0]: a parallel step has only the keys 'parallel' and 'metadata', got 'times'",
            messageFor("""{"steps":[{"parallel":{"steps":[{"text":"a"}]},"metadata":{"name":"g"},"times":{"min":1}}]}"""),
        )
    }

    @Test
    fun `names need not be unique`() {
        val s = decode("""{"steps":[{"metadata":{"name":"same"},"text":"a"},{"metadata":{"name":"same"},"text":"b"}]}""")
        val engine = ScenarioEngine().also { it.loadInline(s) }
        engine.handle("anthropic", user("x"))
        engine.handle("anthropic", user("x"))
        assertEquals(listOf("same", "same"), engine.calls().map { it.stepName })
    }
}
