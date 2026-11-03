// Shared test helpers, not one class's file: TestClient plus the request and response helpers.
@file:Suppress("MatchingDeclarationName")

package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TestClient(
    val port: Int,
) {
    private val http =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    fun post(
        path: String,
        body: String,
        authorization: String? = null,
        timeout: Duration = Duration.ofSeconds(15),
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> =
        send(
            request(path).POST(HttpRequest.BodyPublishers.ofString(body)).also { b -> headers.forEach { (k, v) -> b.header(k, v) } },
            authorization,
            timeout,
        )

    fun get(
        path: String,
        authorization: String? = null,
    ): HttpResponse<String> = send(request(path).GET(), authorization, Duration.ofSeconds(15))

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))

    private fun send(
        builder: HttpRequest.Builder,
        authorization: String?,
        timeout: Duration,
    ): HttpResponse<String> {
        authorization?.let { builder.header("Authorization", it) }
        val response = http.send(builder.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())
        return response
    }
}

/** `GET /control/exchanges`, parsed. */
internal fun exchanges(client: TestClient): JsonArray = Json.parseToJsonElement(client.get("/control/exchanges").body()).jsonArray

internal fun withServer(
    engine: ScenarioEngine = ScenarioEngine(),
    controlToken: String? = null,
    block: (client: TestClient, engine: ScenarioEngine) -> Unit,
) {
    startFakeLlmServer(port = 0, engine = engine, controlToken = controlToken).use { server ->
        check(server.port != 0) { "test server did not bind an ephemeral port" }
        block(TestClient(server.port), engine)
    }
}

internal fun anthropicBody(
    prompt: String,
    stream: Boolean = false,
    model: String = "example-model",
): String {
    val streamField = if (stream) ""","stream":true""" else ""
    return buildString {
        append("""{"model":""")
        append(JsonPrimitive(model))
        append(""","max_tokens":256""")
        append(streamField)
        append(""","messages":[{"role":"user","content":""")
        append(JsonPrimitive(prompt))
        append("}]}")
    }
}

internal fun anthropicText(body: String): String {
    val content =
        Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("content")
            .jsonArray
    val block =
        content.first {
            it.jsonObject
                .getValue("type")
                .jsonPrimitive.content == "text"
        }
    return block.jsonObject
        .getValue("text")
        .jsonPrimitive.content
}

internal fun anthropicStopReason(body: String): String =
    Json
        .parseToJsonElement(body)
        .jsonObject
        .getValue("stop_reason")
        .jsonPrimitive.content

/** The `message` of one of fake-llm's own error [body]s, after checking that its code is [code] when one is given. */
internal fun errorMessage(
    body: String,
    code: String? = null,
): String {
    val obj = Json.parseToJsonElement(body).jsonObject
    code?.let { assertEquals(it, obj.getValue("fake_llm_error").jsonPrimitive.content, body) }
    return obj.getValue("message").jsonPrimitive.content
}

/** Runs [block] with a new temporary directory, deleted afterwards. */
@OptIn(ExperimentalPathApi::class)
internal fun withScenarioDir(block: (Path) -> Unit) {
    val dir = createTempDirectory("fake-llm-scenarios")
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

/** Writes [json] to `<dir>/<name>.json`. */
internal fun writeScenario(
    dir: Path,
    name: String,
    json: String,
) {
    dir.resolve("$name.json").writeText(json)
}

/**
 * Scenarios that both the server's decoder and the scenario schemas (the spec's and the generated
 * file) must reject, each with the reason it is invalid.
 */
internal val invalidScenarios =
    listOf(
        "no steps" to """{"metadata":{"name":"x"},"steps":[]}""",
        "a top-level name, now metadata.name" to """{"name":"x","steps":[{"text":"t"}]}""",
        "a top-level description, now metadata.description" to """{"description":"d","steps":[{"text":"t"}]}""",
        "an unknown key in the scenario's metadata" to """{"metadata":{"name":"x","owner":"me"},"steps":[{"text":"t"}]}""",
        "an unknown key in a step's metadata" to """{"steps":[{"text":"t","metadata":{"title":"x"}}]}""",
        "metadata in a match" to """{"steps":[{"text":"t","match":{"model":"m","metadata":{"name":"x"}}}]}""",
        "metadata in a text match" to """{"steps":[{"text":"t","match":{"lastUser":{"contains":"a","metadata":{}}}}]}""",
        "metadata in an error" to """{"steps":[{"error":{"status":500,"metadata":{"name":"x"}}}]}""",
        "metadata in a tool call" to """{"steps":[{"toolCalls":[{"name":"f","metadata":{"name":"x"}}]}]}""",
        "metadata in a parallel section" to """{"steps":[{"parallel":{"steps":[{"text":"a"}],"metadata":{"name":"x"}}}]}""",
        "an empty metadata name" to """{"metadata":{"name":""},"steps":[{"text":"t"}]}""",
        "an empty tag" to """{"steps":[{"text":"t","metadata":{"tags":["ok",""]}}]}""",
        "a tag that is not a string" to """{"steps":[{"text":"t","metadata":{"tags":[1]}}]}""",
        "tags that are not a list" to """{"steps":[{"text":"t","metadata":{"tags":"smoke"}}]}""",
        "null tags" to """{"steps":[{"text":"t","metadata":{"tags":null}}]}""",
        "metadata that is not an object" to """{"metadata":"x","steps":[{"text":"t"}]}""",
        "a metadata name that is not a string" to """{"steps":[{"text":"t","metadata":{"name":2}}]}""",
        "an unknown field" to """{"metadata":{"name":"x"},"steps":[{"text":"t"}],"extra":1}""",
        "the removed rules format" to """{"metadata":{"name":"x"},"rules":[{"role":"r","respond":[{"text":"t"}]}]}""",
        "a null steps list" to """{"metadata":{"name":"x"},"steps":null}""",
        "a step with text and error" to """{"metadata":{"name":"x"},"steps":[{"text":"t","error":{"status":500}}]}""",
        "a step with neither" to """{"metadata":{"name":"x"},"steps":[{}]}""",
        "a step with both null" to """{"metadata":{"name":"x"},"steps":[{"text":null,"error":null}]}""",
        "thinking on an error step" to
            """{"metadata":{"name":"x"},"steps":[{"thinking":"h","error":{"status":500}}]}""",
        "streamErrorAfterChunks on a text step" to
            """{"metadata":{"name":"x"},"steps":[{"text":"t","streamErrorAfterChunks":2}]}""",
        "streamErrorAfterChunks of 0" to
            """{"metadata":{"name":"x"},"steps":[{"error":{"status":500},"streamErrorAfterChunks":0}]}""",
        "streamChunkChars of 0" to """{"metadata":{"name":"x"},"steps":[{"text":"t","streamChunkChars":0}]}""",
        "streamChunkChars past Int" to
            """{"metadata":{"name":"x"},"steps":[{"text":"t","streamChunkChars":3000000000}]}""",
        "a negative delay" to """{"metadata":{"name":"x"},"steps":[{"text":"t","streamInterChunkDelayMs":-1}]}""",
        "an unknown field in a step" to """{"metadata":{"name":"x"},"steps":[{"text":"t","extra":1}]}""",
        "an error status of 42" to """{"metadata":{"name":"x"},"steps":[{"error":{"status":42}}]}""",
        "an error status of 600" to """{"metadata":{"name":"x"},"steps":[{"error":{"status":600}}]}""",
        "an error with no status" to """{"metadata":{"name":"x"},"steps":[{"error":{"body":"b"}}]}""",
        "an unknown field in an error" to """{"metadata":{"name":"x"},"steps":[{"error":{"status":500,"extra":1}}]}""",
        "a null stopReason" to """{"metadata":{"name":"x"},"steps":[{"text":"t","stopReason":null}]}""",
        "a delay above the cap" to """{"metadata":{"name":"x"},"steps":[{"text":"t","streamInterChunkDelayMs":600001}]}""",
        "a negative first-byte delay" to """{"metadata":{"name":"x"},"steps":[{"text":"t","firstByteDelayMs":-1}]}""",
        "a first-byte delay above the cap" to """{"metadata":{"name":"x"},"steps":[{"text":"t","firstByteDelayMs":600001}]}""",
        "a null first-byte delay" to """{"metadata":{"name":"x"},"steps":[{"text":"t","firstByteDelayMs":null}]}""",
        "a null delay" to """{"metadata":{"name":"x"},"steps":[{"text":"t","streamInterChunkDelayMs":null}]}""",
        "a null error body" to """{"metadata":{"name":"x"},"steps":[{"error":{"status":500,"body":null}}]}""",
        "null error headers" to """{"metadata":{"name":"x"},"steps":[{"error":{"status":500,"headers":null}}]}""",
        "a non-string error header" to
            """{"metadata":{"name":"x"},"steps":[{"error":{"status":500,"headers":{"a":1}}}]}""",
        "a quoted integer" to """{"metadata":{"name":"x"},"steps":[{"text":"t","streamChunkChars":"24"}]}""",
        "a quoted error status" to """{"metadata":{"name":"x"},"steps":[{"error":{"status":"500"}}]}""",
        "a null \$schema" to """{"${'$'}schema":null,"metadata":{"name":"x"},"steps":[{"text":"t"}]}""",
        "a numeric \$schema" to """{"${'$'}schema":1,"metadata":{"name":"x"},"steps":[{"text":"t"}]}""",
        "\$schema in a step" to """{"metadata":{"name":"x"},"steps":[{"${'$'}schema":"s","text":"t"}]}""",
        "toolCalls with an error" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"name":"f"}],"error":{"status":500}}]}""",
        "an empty toolCalls list" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[]}]}""",
        "a null toolCalls with no text" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":null}]}""",
        "a tool call without a name" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"arguments":{}}]}]}""",
        "an empty tool name" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"name":""}]}]}""",
        "an empty tool call id" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"name":"f","id":""}]}]}""",
        "arguments and rawArguments" to
            """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"name":"f","arguments":{},"rawArguments":"{}"}]}]}""",
        "array arguments" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"name":"f","arguments":[1]}]}]}""",
        "object rawArguments" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"name":"f","rawArguments":{}}]}]}""",
        "max_tokens with toolCalls" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"name":"f"}],"stopReason":"max_tokens"}]}""",
        "an unknown field in a tool call" to """{"metadata":{"name":"x"},"steps":[{"toolCalls":[{"name":"f","input":{}}]}]}""",
        "usage on an error step" to """{"metadata":{"name":"x"},"steps":[{"error":{"status":500},"usage":{"inputTokens":1}}]}""",
        "a negative inputTokens" to """{"metadata":{"name":"x"},"steps":[{"text":"t","usage":{"inputTokens":-1}}]}""",
        "an unknown field in usage" to """{"metadata":{"name":"x"},"steps":[{"text":"t","usage":{"cachedTokens":1}}]}""",
        "stopSequence without stopReason stop_sequence" to """{"metadata":{"name":"x"},"steps":[{"text":"t","stopSequence":"END"}]}""",
        "an empty stopSequence" to """{"metadata":{"name":"x"},"steps":[{"text":"t","stopReason":"stop_sequence","stopSequence":""}]}""",
        "outputTokens past Int" to """{"metadata":{"name":"x"},"steps":[{"text":"t","usage":{"outputTokens":3000000000}}]}""",
        "a parallel step inside a parallel step" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[{"parallel":{"steps":[{"text":"a"}]}}]}}]}""",
        "a parallel step with a sibling key" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[{"text":"a"}]},"text":"t"}]}""",
        "a parallel step with an explicit default beside it" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[{"text":"a"}]},"stopReason":"end_turn"}]}""",
        "a parallel step with a null sibling" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[{"text":"a"}]},"text":null}]}""",
        "match on a parallel step" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"model":"m"},"parallel":{"steps":[{"text":"a"}]}}]}""",
        "a null parallel and nothing else" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":null}]}""",
        "an empty parallel steps list" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[]}}]}""",
        "a parallel section without steps" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"overlap":true}}]}""",
        "an unknown field in a parallel section" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[{"text":"a"}],"x":1}}]}""",
        "overlap as a string" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[{"text":"a"}],"overlap":"yes"}}]}""",
        "an inner step with neither a reply nor an error" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[{"text":"a"},{}]}}]}""",
        "an empty match" to
            """{"metadata":{"name":"x"},"steps":[{"match":{},"text":"t"}]}""",
        "a match whose only key is null" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"model":null},"text":"t"}]}""",
        "an unknown key in a match" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"modle":"m"},"text":"t"}]}""",
        "a text match with two operators" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"lastUser":{"contains":"a","equals":"b"}},"text":"t"}]}""",
        "a text match with no operator" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"lastUser":{}},"text":"t"}]}""",
        "a text match whose only operator is null" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"system":{"contains":null}},"text":"t"}]}""",
        "a message match with index and role" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"message":{"index":0,"role":"user","contains":"a"}},"text":"t"}]}""",
        "a message match with neither index nor role" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"message":{"contains":"a"}},"text":"t"}]}""",
        "a message match with no text operator" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"message":{"index":0}},"text":"t"}]}""",
        "a quoted message index" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"message":{"index":"0","contains":"a"}},"text":"t"}]}""",
        "an unknown route" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"route":"bedrock"},"text":"t"}]}""",
        "stream as a string" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"stream":"true"},"text":"t"}]}""",
        "a negative messageCount" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"messageCount":-1},"text":"t"}]}""",
        "a body path without $" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"metadata.user","equals":1}},"text":"t"}]}""",
        "a body path that is only $" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$","equals":1}},"text":"t"}]}""",
        "a body path with a wildcard" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$.a[*]","equals":1}},"text":"t"}]}""",
        "a body path with an unclosed quote" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$['a]","equals":1}},"text":"t"}]}""",
        "a body match without equals" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$.a"}},"text":"t"}]}""",
        "an empty tools match" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"tools":{}},"text":"t"}]}""",
        "an unknown tool choice" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"tools":{"choice":"maybe"}},"text":"t"}]}""",
        "choiceTool with choice auto" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"tools":{"choice":"auto","choiceTool":"f"}},"text":"t"}]}""",
        "an empty choiceTool" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"tools":{"choiceTool":""}},"text":"t"}]}""",
        "an empty toolResult match" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"toolResult":{}},"text":"t"}]}""",
        "a toolResult match whose only key is null" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"toolResult":{"id":null}},"text":"t"}]}""",
        "an empty header name" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"header":{"name":"","contains":"a"}},"text":"t"}]}""",
        "a header name with a space" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"header":{"name":"x trace","contains":"a"}},"text":"t"}]}""",
        "a body path index of ten digits" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$[1234567890]","equals":1}},"text":"t"}]}""",
        "a body path with a bad escape" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$['a\\b']","equals":1}},"text":"t"}]}""",
        "a body path name starting with a digit" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$.1a","equals":1}},"text":"t"}]}""",
        "a body path with a double dot" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$..a","equals":1}},"text":"t"}]}""",
        "a body path name with a space" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$.a b","equals":1}},"text":"t"}]}""",
        "a body path ending in a newline" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"body":{"path":"$.a\n","equals":1}},"text":"t"}]}""",
        "a header name ending in a newline" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"header":{"name":"x-trace\n","contains":"a"}},"text":"t"}]}""",
        "a tools match whose only key is null" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"tools":{"declares":null}},"text":"t"}]}""",
        "a header match whose only operator is null" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"header":{"name":"x","contains":null}},"text":"t"}]}""",
        "a negative times.min" to
            """{"metadata":{"name":"x"},"steps":[{"times":{"min":-1},"text":"t"}]}""",
        "a times.max of 0" to
            """{"metadata":{"name":"x"},"steps":[{"times":{"max":0},"text":"t"}]}""",
        "times.max as a string" to
            """{"metadata":{"name":"x"},"steps":[{"times":{"max":"3"},"text":"t"}]}""",
        "an unknown key in times" to
            """{"metadata":{"name":"x"},"steps":[{"times":{"min":1,"count":2},"text":"t"}]}""",
        "a null times.min" to
            """{"metadata":{"name":"x"},"steps":[{"times":{"min":null},"text":"t"}]}""",
        "times on a parallel step" to
            """{"metadata":{"name":"x"},"steps":[{"parallel":{"steps":[{"text":"a"}]},"times":{"max":2}}]}""",
        "times as a number" to
            """{"metadata":{"name":"x"},"steps":[{"times":2,"text":"t"}]}""",
        "a header match with no operator" to
            """{"metadata":{"name":"x"},"steps":[{"match":{"header":{"name":"x-trace"}},"text":"t"}]}""",
    )

/**
 * Scenarios with parallel steps and matches that the decoder, `fake-llm validate`, the scenario
 * schemas and the engine must all accept, including explicit nulls the decoder reads as absent.
 */
internal val validParallelScenarios =
    listOf(
        "metadata on the scenario and on every kind of step" to
            """{"metadata":{"name":"note-summary","description":"Single note","tags":["smoke"]},"steps":[
              {"metadata":{"name":"summarize-note","tags":["happy-path"]},"text":"ok"},
              {"metadata":{"name":"rate-limited","description":"One 429 first"},"error":{"status":429}},
              {"metadata":{"description":"Two tool results can arrive in either order","tags":["race"]},"parallel":{"steps":[
                {"metadata":{"name":"side-a"},"match":{"lastUser":{"contains":"a"}},"text":"a"},
                {"metadata":{"name":"side-b","description":null},"text":"b"}]}},
              {"metadata":null,"text":"no metadata"},
              {"metadata":{},"text":"empty metadata"}]}""",
        "the issue's fan-out and merge" to
            """{"metadata":{"name":"summarize-three-then-merge"},"steps":[{"parallel":{"steps":[
              {"match":{"lastUser":{"contains":"doc-a"}},"text":"Summary of A."},
              {"match":{"lastUser":{"contains":"doc-b"}},"text":"Summary of B."},
              {"match":{"lastUser":{"contains":"doc-c"}},"text":"Summary of C."}]}},
              {"match":{"system":{"contains":"Merge"}},"text":"A, B and C, combined."}]}""",
        "the issue's 429 and retry" to
            """{"metadata":{"name":"parallel-with-a-429-retry"},"steps":[{"parallel":{"steps":[
              {"match":{"lastUser":{"contains":"doc-a"}},"error":{"status":429,"body":"{\"type\":\"error\"}","headers":{"retry-after":"1"}}},
              {"match":{"lastUser":{"contains":"doc-a"}},"text":"Summary of A."},
              {"match":{"lastUser":{"contains":"doc-b"}},"text":"Summary of B."}]}}]}""",
        "a nested fan-out in an overlap group" to
            """{"metadata":{"name":"nested"},"steps":[
              {"parallel":{"overlap":true,"steps":[{"match":{"lastUser":{"equals":"plan a"}},"text":"A"},
                {"match":{"lastUser":{"equals":"plan b"}},"text":"B"}]}},
              {"parallel":{"overlap":true,"steps":[{"match":{"lastUser":{"equals":"a1"}},"text":"A1"},
                {"match":{"lastUser":{"equals":"a2"}},"text":"A2"}]}},
              {"parallel":{"overlap":true,"steps":[{"match":{"lastUser":{"equals":"b1"}},"text":"B1"}]}},
              {"text":"merged"}]}""",
        "every match key" to
            """{"metadata":{"name":"keys"},"steps":[{"match":{"route":"anthropic","model":"m","stream":false,
              "system":{"contains":"s"},"lastUser":{"equals":"u"},"prompt":{"regex":"^s"},
              "message":{"index":-1,"contains":"u"},"messageCount":1,"hasSystem":true,
              "toolResult":{"id":"t1","name":"f","content":{"contains":"x"},"isError":false},
              "tools":{"declares":"f","choice":"tool","choiceTool":"f"},
              "body":{"path":"$.metadata['user \\'id'][-1].a","equals":{"a":[1,null]}},
              "header":{"name":"x-trace","contains":"a"}},"text":"t"}]}""",
        "explicit nulls the decoder reads as absent" to
            """{"metadata":{"name":"nulls"},"steps":[
              {"match":{"lastUser":{"contains":"a","equals":null,"regex":null},"model":null},"text":"t"},
              {"parallel":{"overlap":false,"steps":[{"text":"a","match":null,"parallel":null}]}},
              {"match":{"message":{"role":"user","index":null,"equals":"x"}},"text":"t"},
              {"match":{"toolResult":{"id":"x","name":null},"tools":{"choice":"auto","choiceTool":null}},"text":"t"},
              {"match":{"body":{"path":"$.a","equals":null}},"text":"t"},
              {"parallel":null,"text":"t"},
              {"match":{"tools":{"choiceTool":"f"}},"text":"t"},
              {"match":{"header":{"name":"X-Trace","regex":"a+","contains":null}},"text":"t"}]}""",
        "body paths the decoder and the schema both accept" to
            """{"metadata":{"name":"paths"},"steps":[{"match":{"body":{"path":"$['a\\\\b']","equals":1}},"text":"t"},
              {"match":{"body":{"path":"$[-0]","equals":1}},"text":"t"},{"match":{"body":{"path":"$['']","equals":1}},"text":"t"},
              {"match":{"body":{"path":"$.a-b[123456789]._x","equals":1}},"text":"t"}]}""",
        "steps used a variable number of times" to
            """{"metadata":{"name":"times"},"steps":[{"times":null,"text":"once"},{"times":{},"match":{"lastUser":{"contains":"poll"}},"text":"p"},
              {"times":{"min":0},"match":{"lastUser":{"contains":"retry"}},"text":"r"},{"times":{"max":3,"min":1},"text":"t"},
              {"times":{"min":2,"max":2},"error":{"status":503}},{"times":{"max":null,"min":0},"text":"u"},
              {"parallel":{"steps":[{"times":{"min":1},"match":{"lastUser":{"contains":"doc-"}},"text":"s"},{"text":"x"}]}}]}""",
        "a lone overlap section and one with no matches" to
            """{"metadata":{"name":"lone"},"steps":[{"parallel":{"overlap":true,"steps":[{"text":"x"},{"text":"y"}]}},
              {"parallel":{"steps":[{"text":"z"}]}}]}""",
    ) + docScenarios("docs/scenarios.md", "Parallel calls")

/**
 * Each JSON scenario (a block with `"name"` and `"steps"`) in the `## [section]` section of the
 * docs page [page], so the examples the docs show are checked like the rest.
 */
private fun docScenarios(
    page: String,
    section: String,
): List<Pair<String, String>> {
    val text = File(page).readText()
    val start = text.indexOf("\n## $section\n")
    check(start >= 0) { "no '## $section' in $page" }
    val end = text.indexOf("\n## ", start + 1).takeIf { it > 0 } ?: text.length
    val blocks =
        Regex("```json\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL)
            .findAll(text.substring(start, end))
            .map { it.groupValues[1] }
            .filter { "\"steps\"" in it && "\"metadata\"" in it }
            .toList()
    check(blocks.size >= 3) { "expected the scenario examples in $page's $section section, found ${blocks.size}" }
    return blocks.mapIndexed { i, block -> "$page example ${i + 1}" to block }
}

/** Every directory of scenario files in the repository, relative to the root project. */
internal val scenarioDirectories =
    listOf(
        "src/main/resources/com/davidjonesgriz/fakellm/scenarios",
        "examples/src/test/resources/scenarios",
        "examples/src/test/scenarios",
        "junit-jupiter/src/test/fixture-scenarios",
        "examples/github-actions/scenarios",
        "testcontainers/src/test/scenarios",
    )

private const val MIN_SCENARIO_FILES = 12

/** The `*.json` files in [scenarioDirectories]. Fails if a directory has none, so a moved directory is noticed. */
internal fun repositoryScenarioFiles(): List<File> {
    val byDirectory = scenarioDirectories.associateWith { dir -> File(dir).listFiles { f -> f.extension == "json" }.orEmpty().toList() }
    byDirectory.forEach { (dir, found) -> assertTrue(found.isNotEmpty(), "no scenario files in $dir") }
    val files = byDirectory.values.flatten()
    assertTrue(files.size >= MIN_SCENARIO_FILES, "found only ${files.map { it.path }}")
    return files
}

/**
 * The event name and data payload of each frame of a named-event stream (Anthropic, OpenAI
 * Responses), in order. Fails unless every frame is exactly one `event:` line and one `data:` line.
 */
internal fun namedFrames(body: String): List<Pair<String, String>> =
    body.split("\n\n").filter { it.isNotBlank() }.map { frame ->
        val lines = frame.lines()
        assertEquals(2, lines.size, "a frame is one event line and one data line: $frame")
        lines[0].also { assertTrue(it.startsWith("event: "), frame) }.removePrefix("event: ") to
            lines[1].also { assertTrue(it.startsWith("data: "), frame) }.removePrefix("data: ")
    }

/** A minimal OpenAI Responses request body: `{"model":"example-gpt","input":"<input>"}`, with `"stream":true` when [stream]. */
internal fun responsesBody(
    input: String = "Hello",
    stream: Boolean = false,
): String = """{"model":"example-gpt","input":${JsonPrimitive(input)}""" + (if (stream) ""","stream":true}""" else "}")

/**
 * The data payloads of a data-only stream (OpenAI, Gemini), in order. Fails unless every frame is
 * exactly one `data:` line (no `event:` line).
 */
internal fun dataFrames(body: String): List<String> {
    val frames = body.split("\n\n").filter { it.isNotEmpty() }
    return frames.map { frame ->
        val lines = frame.split("\n")
        assertTrue(lines.size == 1 && lines[0].startsWith("data: "), "not a single data: line: $frame")
        lines[0].removePrefix("data: ")
    }
}
