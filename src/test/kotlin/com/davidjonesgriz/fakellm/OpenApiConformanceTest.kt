package com.davidjonesgriz.fakellm

import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Checks the server against openapi/fake-llm.yaml, the hand-written contract. Each exchange in
 * [exchanges] runs against a real server: its status must be documented for the operation, its
 * Content-Type and documented headers must match, and its body must validate against the
 * documented schema. Response schemas are checked strictly (undocumented fields fail), though the
 * spec itself leaves them open for clients. Request bodies are validated too, and must be valid
 * exactly when the exchange says so. Every documented status of /health, /openapi and the control
 * routes must be exercised.
 */
class OpenApiConformanceTest {
    @Test
    fun `every exchange conforms to the spec, and every documented control status is exercised`() {
        val exercised = mutableSetOf<Triple<String, String, String>>()
        val failures =
            exchanges.mapNotNull { exchange ->
                runCatching { exercised += check(exchange) }.exceptionOrNull()?.let { "${exchange.name}: ${it.message}" }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))

        val documented =
            spec.path("paths").properties().flatMap { (path, item) ->
                item.properties().filter { (method, _) -> method in HTTP_METHODS }.flatMap { (method, operation) ->
                    operation.path("responses").propertyNames().map { status -> Triple(path, method, status) }
                }
            }
        val mustCover = documented.filter { (path) -> path == "/health" || path == "/openapi" || path.startsWith("/control/") }
        assertEquals(emptyList(), mustCover - exercised - NOT_REACHABLE_OVER_HTTP, "documented but never exercised")
    }

    @Test
    fun `validation is not vacuous`() {
        // A response body with an undocumented field must fail the strict check, and a request body
        // the server rejects must fail the request schema.
        val health = "#/paths/~1health/get/responses/200/content/application~1json/schema"
        assertEquals(emptyList(), validate(health, """{"status":"ok","scenario":null,"lenient":false}"""))
        assertTrue(validate(health, """{"status":"ok","scenario":null,"lenient":false,"extra":1}""").isNotEmpty())
        val load = "#/paths/~1control~1scenario/post/requestBody/content/application~1json/schema"
        assertEquals(emptyList(), validate(load, """{"name":"hello"}"""))
        assertTrue(validate(load, """{"name":"hello","extra":1}""").isNotEmpty())
    }

    @Test
    fun `strictness is injected only into response schemas, never into the request and scenario schemas`() {
        assertEquals(emptySet(), strictSchemas intersect REQUEST_SCHEMAS.map { "#/components/schemas/$it" }.toSet())
        assertTrue("#/components/schemas/CallRecord" in strictSchemas, "$strictSchemas")
    }

    @Test
    fun `every scenario file in the repository conforms to the Scenario schema`() {
        val byDirectory =
            listOf(
                "src/main/resources/com/davidjonesgriz/fakellm/scenarios",
                "examples/src/test/resources/scenarios",
                "examples/src/test/scenarios",
                "junit-jupiter/src/test/fixture-scenarios",
            ).associateWith { dir -> File(dir).listFiles { f -> f.extension == "json" }.orEmpty().toList() }
        byDirectory.forEach { (dir, found) -> assertTrue(found.isNotEmpty(), "no scenario files in $dir") }
        val files = byDirectory.values.flatten()
        assertTrue(files.size >= MIN_SCENARIO_FILES, "found only ${files.map { it.path }}")
        files.forEach { file ->
            assertEquals(emptyList(), validate(SCENARIO, file.readText()), file.path)
        }
    }

    @Test
    fun `the Scenario schema and the server's decoder reject the same invalid scenarios`() {
        invalidScenarios.forEach { (why, body) ->
            assertTrue(validate(SCENARIO, body).isNotEmpty(), "schema accepted: $why")
            assertFails("decoder accepted: $why") {
                ScenarioLoader.decode(
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(body),
                )
            }
        }
    }

    @Test
    fun `the Scenario schema and the server's decoder both accept explicit nulls`() {
        val withNulls =
            """{"name":"nulls","steps":[
              {"text":null,"thinking":null,"error":{"status":500}},
              {"text":"t","error":null,"streamErrorAfterChunks":null}
            ]}"""
        assertEquals(emptyList(), validate(SCENARIO, withNulls))
        ScenarioLoader.decode(
            kotlinx.serialization.json.Json
                .parseToJsonElement(withNulls),
        )
    }

    @Test
    fun `integers are written without a fraction, as the spec says`() {
        // JSON Schema treats 24.0 as an integer, so the rule is stated in the spec's description;
        // the server's decoder enforces it.
        val integralFloat = """{"name":"x","steps":[{"text":"t","streamChunkChars":24.0}]}"""
        assertContains(spec.path("info").path("description").asString(), "without a fraction or exponent")
        assertFails {
            ScenarioLoader.decode(
                kotlinx.serialization.json.Json
                    .parseToJsonElement(integralFloat),
            )
        }
    }

    @Test
    fun `every conformance case agrees with the spec`() {
        val files =
            File("conformance/cases")
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .sortedBy { it.path }
                .toList()
        assertTrue(files.isNotEmpty(), "no case files in conformance/cases")
        val failures =
            files.flatMap { file ->
                val case =
                    runCatching {
                        json.readTree(
                            file.readText(),
                        )
                    }.getOrElse { return@flatMap listOf("${file.path}: not JSON: ${it.message}") }
                caseDisagreements(case).map { "${file.path}: $it" }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the case check is not vacuous`() {
        fun case(response: String) =
            json.readTree(
                """{"name":"x","description":"d","scenario":{"name":"s","steps":[{"text":"t"}]},
                "exchanges":[{"request":{"method":"GET","path":"/health"},"response":$response}]}""",
            )
        assertEquals(emptyList(), caseDisagreements(case("""{"status":200,"body":{"status":"ok","scenario":"s","lenient":false}}""")))
        assertTrue(caseDisagreements(case("""{"status":200,"body":{"status":"ok","scenario":"s","lenient":false,"x":1}}""")).isNotEmpty())
        assertTrue(caseDisagreements(case("""{"status":418}""")).isNotEmpty())
        assertTrue(caseDisagreements(case("""{"status":200,"headers":{"content-type":"text/plain"}}""")).isNotEmpty())
        val badScenario = json.readTree("""{"scenario":{"name":"s","steps":[]},"exchanges":[]}""")
        assertTrue(caseDisagreements(badScenario).isNotEmpty())

        fun vendor400(body: String) =
            json.readTree(
                """{"scenario":"hello","exchanges":[{"request":{"method":"POST","path":"/anthropic/v1/messages"},
                "response":{"status":400$body}}]}""",
            )
        assertEquals(emptyList(), caseDisagreements(vendor400(""","body":{"type":"error"}""")), "a scripted 400")
        assertTrue(caseDisagreements(vendor400(""","body":{"fake_llm_error":"no more step"}""")).isNotEmpty(), "a misspelt own 400")
        assertTrue(caseDisagreements(vendor400("")).isNotEmpty(), "a vendor 400 with no body")

        fun control401(server: String) =
            json.readTree(
                """{"scenario":"hello",$server"exchanges":[{"request":{"method":"GET","path":"/control/nope"},
                "response":{"status":401,"headers":{"www-authenticate":"Bearer"},"body":{"fake_llm_error":"x"}}}]}""",
            )
        assertEquals(emptyList(), caseDisagreements(control401(""""server":{"controlToken":"t"},""")))
        assertTrue(caseDisagreements(control401("")).isNotEmpty(), "a 401 without a control token")
    }

    /** How one conformance case disagrees with the spec; empty when it agrees. */
    private fun caseDisagreements(case: JsonNode): List<String> {
        val found = mutableListOf<String>()
        val tokenSet = case.path("server").path("controlToken").isString
        case.path("scenario").takeIf { it.isObject }?.let { scenario ->
            validate(SCENARIO, scenario.toString()).forEach { found += "scenario: $it" }
        }
        case.path("exchanges").values().forEachIndexed { i, exchange ->
            val request = exchange.path("request")
            val response = exchange.path("response")
            val method = request.path("method").asString()
            val path = request.path("path").asString().substringBefore('?')
            val status = response.path("status").asInt()
            val where = "exchange ${i + 1} ($method $path $status)"
            val successfulLoad = method == "POST" && path == "/control/scenario" && status == HTTP_OK && request.has("body")
            if (successfulLoad) {
                val load = "${operationPointer(path, method)}/requestBody/content/application~1json/schema"
                validate(load, request.path("body").toString()).forEach { found += "$where request: $it" }
            }
            val pointer =
                responsePointerFor(method, path, status, response.path("body"), tokenSet)
                    .getOrElse { error ->
                        found += "$where: ${error.message}"
                        return@forEachIndexed
                    } ?: return@forEachIndexed
            found += responseDisagreements(resolve(pointer), response).map { "$where: $it" }
        }
        return found
    }

    /**
     * The spec's response for a request, or null for a scripted error, which the spec leaves
     * undescribed. A vendor 400 is fake-llm's own only when its `fake_llm_error` says so; any other
     * 400 body there is a scripted error step.
     */
    private fun responsePointerFor(
        method: String,
        path: String,
        status: Int,
        body: JsonNode,
        tokenSet: Boolean,
    ): Result<String?> {
        val template =
            spec.path("paths").propertyNames().firstOrNull { template ->
                templateRegex(template).matches(path) &&
                    spec.path("paths").path(template).has(method.lowercase())
            }
        return when {
            template != null && status == HTTP_UNAUTHORIZED && !tokenSet -> {
                Result.failure(IllegalStateException("a 401 needs a control token, and the case sets none"))
            }

            template != null -> {
                operationResponse(template, method, status, body)
            }

            path.startsWith("/anthropic/v1/messages/batches/") -> {
                expect(status, 501, "#/components/responses/NotImplemented")
            }

            path.startsWith("/control/") && tokenSet -> {
                expect(status, HTTP_UNAUTHORIZED, "#/components/responses/Unauthorized")
            }

            else -> {
                expect(status, 404, "#/components/responses/NoRoute")
            }
        }
    }

    /** The response [status] of one documented operation, or null for a scripted vendor error. */
    private fun operationResponse(
        template: String,
        method: String,
        status: Int,
        body: JsonNode,
    ): Result<String?> {
        val operation = operationPointer(template, method)
        val responses = spec.atRef("$operation/responses")
        val vendor = listOf("/anthropic/", "/openai/", "/gemini/").any { template.startsWith(it) }
        // A vendor 400 whose body has fake_llm_error is fake-llm's own and is checked against
        // VendorBadRequest (so a misspelt value fails); any other 400 body is a scripted error.
        val vendor400 = vendor && status == HTTP_BAD_REQUEST
        return when {
            vendor400 && body.isMissingNode -> {
                Result.failure(
                    IllegalStateException("a vendor 400 must list its body, to tell a scripted 400 from fake-llm's own"),
                )
            }

            vendor400 && !body.has("fake_llm_error") -> {
                Result.success(null)
            }

            responses.has("$status") -> {
                Result.success("$operation/responses/$status")
            }

            vendor && responses.has("default") -> {
                Result.success(null)
            }

            else -> {
                Result.failure(IllegalStateException("status $status is not documented"))
            }
        }
    }

    /** A path template such as `/gemini/{version}/models/{model}:generateContent` as a regex for concrete paths. */
    private fun templateRegex(template: String): Regex =
        Regex(template.split(Regex("\\{[^}]+}")).joinToString("[^/]+") { Regex.escape(it) })

    private fun expect(
        status: Int,
        documented: Int,
        pointer: String,
    ): Result<String?> =
        if (status ==
            documented
        ) {
            Result.success(pointer)
        } else {
            Result.failure(IllegalStateException("expected $documented here, as the spec documents"))
        }

    private fun responseDisagreements(
        responsePointer: String,
        response: JsonNode,
    ): List<String> {
        val found = mutableListOf<String>()
        val documented = spec.atRef(responsePointer)
        val headers = response.path("headers").properties().associate { (name, value) -> name.lowercase() to value.asString() }
        documented.path("headers").properties().forEach { (name, header) ->
            val value = headers[name.lowercase()]
            if (value == null) {
                found += "the documented header $name is not listed"
            } else {
                val headerSchema = resolve(pointerOf(header) ?: "$responsePointer/headers/${escape(name)}") + "/schema"
                validate(headerSchema, json.writeValueAsString(value)).forEach { found += "header $name: $it" }
            }
        }
        val media = documented.path("content").propertyNames().toList()
        headers["content-type"]?.let { contentType ->
            val type = contentType.substringBefore(';').trim()
            if (media.isNotEmpty() && type !in media) found += "content-type $type is not documented ($media)"
        }
        val body = response.path("body")
        val schemaMedia = headers["content-type"]?.substringBefore(';')?.trim() ?: media.singleOrNull()
        if (!body.isMissingNode && schemaMedia == "application/json" && "application/json" in media) {
            validate("$responsePointer/content/application~1json/schema", withSamples(body).toString()).forEach { found += "body: $it" }
        }
        return found
    }

    /** [node] with each `$type` matcher replaced by a value of that type. */
    private fun withSamples(node: JsonNode): JsonNode =
        when {
            node.isObject && node.propertyNames().toList() == listOf("\$type") -> {
                if (node.path("\$type").asString() == "integer") json.readTree("0") else json.readTree("\"sample\"")
            }

            node.isObject -> {
                json.createObjectNode().also { obj -> node.properties().forEach { (k, v) -> obj.set(k, withSamples(v)) } }
            }

            node.isArray -> {
                json.createArrayNode().also { arr -> node.values().forEach { arr.add(withSamples(it)) } }
            }

            else -> {
                node
            }
        }

    @Test
    fun `the spec's version is the build's version`() {
        val version = File("gradle.properties").readLines().first { it.startsWith("version=") }.removePrefix("version=")
        assertEquals(version, spec.path("info").path("version").asString())
    }

    /** Runs [exchange] and checks it against the spec; returns the (path, method, status) it exercised. */
    private fun check(exchange: Exchange): Triple<String, String, String> {
        val engine = ScenarioEngine(lenient = exchange.lenient)
        startFakeLlmServer(port = 0, engine = engine, controlToken = exchange.serverToken).use { server ->
            exchange.setup(server)
            val response = send(server, exchange.method, exchange.path, exchange.body, exchange.authorization)
            val status = response.statusCode().toString()
            val responsePointer =
                when (val target = exchange.target) {
                    is Target.Operation -> {
                        val operation = operationPointer(target.path, exchange.method)
                        check(!spec.atRef(operation).isMissingNode) { "no operation ${exchange.method} ${target.path} in the spec" }
                        checkRequest(exchange, operation)
                        val pointer = "$operation/responses/$status"
                        check(!spec.atRef(pointer).isMissingNode) {
                            "status $status is not documented for ${exchange.method} ${target.path} (body ${response.body()})"
                        }
                        pointer
                    }

                    is Target.Response -> {
                        assertEquals(target.status, status, response.body())
                        "#/components/responses/${target.name}"
                    }
                }
            if (status == "401") {
                assertTrue(
                    spec.atRef(resolve(responsePointer)).path("headers").has("WWW-Authenticate"),
                    "the 401 response documents WWW-Authenticate",
                )
            }
            checkResponse(resolve(responsePointer), response)
            exchange.expectStatus?.let { assertEquals(it, response.statusCode(), response.body()) }
            exchange.assertBody(response.body())
            val path = (exchange.target as? Target.Operation)?.path ?: "#/components/responses/${(exchange.target as Target.Response).name}"
            return Triple(path, exchange.method.lowercase(), status)
        }
    }

    private fun checkRequest(
        exchange: Exchange,
        operation: String,
    ) {
        val schema = "$operation/requestBody/content/application~1json/schema"
        if (exchange.body == null || spec.atRef(schema).isMissingNode) return
        // A body that isn't JSON at all is invalid; any other exception (a broken $ref, say) fails the test.
        val parsed = runCatching { json.readTree(exchange.body) }.getOrNull()?.takeUnless { it.isMissingNode }
        val valid = parsed != null && validate(schema, exchange.body).isEmpty()
        assertEquals(exchange.requestValid, valid, "request body validity against the spec: ${exchange.body}")
    }

    private fun checkResponse(
        responsePointer: String,
        response: HttpResponse<String>,
    ) {
        val documented = spec.atRef(responsePointer)
        documented.path("headers").properties().forEach { (name, header) ->
            val values = response.headers().allValues(name)
            assertEquals(1, values.size, "header $name: $values")
            val headerSchema = resolve(pointerOf(header) ?: "$responsePointer/headers/${escape(name)}") + "/schema"
            assertEquals(emptyList(), validate(headerSchema, json.writeValueAsString(values.single())), "header $name")
        }
        val media = documented.path("content").propertyNames().toList()
        if (media.isEmpty()) return
        val contentTypes = response.headers().allValues("content-type")
        assertEquals(1, contentTypes.size, "Content-Type: $contentTypes")
        val mediaType = contentTypes.single()
        assertTrue(mediaType in media, "Content-Type $mediaType is not documented ($media)")
        val schema = "$responsePointer/content/${escape(mediaType)}/schema"
        val body = if (mediaType == "application/json") response.body() else json.writeValueAsString(response.body())
        assertEquals(emptyList(), validate(schema, body), "response body ${response.body()}")
    }

    private sealed interface Target {
        /** An operation in `paths`, by its path template. */
        data class Operation(
            val path: String,
        ) : Target

        /** A reusable response under `components.responses`, for routes no operation describes. */
        data class Response(
            val name: String,
            val status: String,
        ) : Target
    }

    private data class Exchange(
        val name: String,
        val method: String,
        val path: String,
        val target: Target,
        val body: String? = null,
        /** Whether [body] should validate against the operation's request schema. */
        val requestValid: Boolean = true,
        val authorization: String? = null,
        val serverToken: String? = null,
        val lenient: Boolean = false,
        val expectStatus: Int? = null,
        val setup: (FakeLlmServer) -> Unit = {},
        val assertBody: (String) -> Unit = {},
    )

    private companion object {
        const val BASE = "https://fake-llm.invalid/openapi.json"
        const val SCENARIO = "#/components/schemas/Scenario"
        const val TOKEN = "spec-token"
        const val MIN_SCENARIO_FILES = 12
        val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
        val REQUEST_SCHEMAS = listOf("LoadRequest", "Scenario", "Step", "ErrorStep")

        /** The `fake_llm_error` values of fake-llm's own vendor 400s (`VendorBadRequest`). */
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401

        /** Documented, but reachable only from a broken build; FakeLlmServerTest covers openApiResponse(null). */
        val NOT_REACHABLE_OVER_HTTP = setOf(Triple("/openapi", "get", "500"))

        val json: JsonMapper = JsonMapper.builder().build()
        val specFile = File("openapi/fake-llm.yaml")
        val spec: JsonNode = YAMLMapper.builder().build().readTree(specFile.readText())

        /** JSON pointers of the object schemas made strict for response checking. */
        val strictSchemas = mutableSetOf<String>()
        val strictSpec: JsonNode = spec.deepCopy().also { tree -> injectStrictness(tree) }
        val registry: SchemaRegistry =
            SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31()) { it.schemas(mapOf(BASE to strictSpec.toString())) }

        fun validate(
            pointer: String,
            body: String,
        ): List<String> =
            registry.getSchema(SchemaLocation.of("$BASE$pointer")).validate(json.readTree(body)).map {
                "${it.instanceLocation} ${it.message}"
            }

        fun escape(token: String): String = token.replace("~", "~0").replace("/", "~1")

        fun operationPointer(
            path: String,
            method: String,
        ): String = "#/paths/${escape(path)}/${method.lowercase()}"

        fun pointerOf(node: JsonNode): String? = node.path("\$ref").takeIf { it.isString }?.asString()

        /** Follows a chain of `$ref`s from [pointer] to the node it finally names. */
        fun resolve(pointer: String): String {
            var current = pointer
            while (true) current = pointerOf(spec.atRef(current)) ?: return current
        }

        fun JsonNode.atRef(pointer: String): JsonNode = this.at(pointer.removePrefix("#"))

        /**
         * Adds `unevaluatedProperties: false` to every schema with `properties` reachable from a documented
         * response, following `$ref`s. Request schemas are never reachable from a response, which
         * [strictSchemas] lets a test assert.
         */
        fun injectStrictness(tree: JsonNode) {
            val roots = mutableListOf<String>()
            tree.path("paths").properties().forEach { (path, item) ->
                item.properties().filter { (method, _) -> method in HTTP_METHODS }.forEach { (method, operation) ->
                    operation.path("responses").propertyNames().forEach { status ->
                        roots += "#/paths/${escape(path)}/$method/responses/$status"
                    }
                }
            }
            tree
                .path("components")
                .path("responses")
                .propertyNames()
                .forEach { roots += "#/components/responses/$it" }
            val visited = mutableSetOf<String>()
            roots.forEach { root ->
                val response = resolveIn(tree, root)
                tree.atRef(response).path("content").propertyNames().forEach { media ->
                    walk(tree, "$response/content/${escape(media)}/schema", visited)
                }
            }
        }

        fun resolveIn(
            tree: JsonNode,
            pointer: String,
        ): String {
            var current = pointer
            while (true) current = pointerOf(tree.atRef(current)) ?: return current
        }

        fun walk(
            tree: JsonNode,
            pointer: String,
            visited: MutableSet<String>,
        ) {
            val target = resolveIn(tree, pointer)
            if (!visited.add(target)) return
            val node = tree.atRef(target) as? ObjectNode ?: return
            // Only schemas that document their properties: a bare `type: object` (a vendor body that
            // follows the provider's API) stays free-form.
            if (node.has("properties") && !node.has("unevaluatedProperties")) {
                node.put("unevaluatedProperties", false)
                strictSchemas += target
            }
            node.path("properties").propertyNames().forEach { walk(tree, "$target/properties/${escape(it)}", visited) }
            if (node.has("items")) walk(tree, "$target/items", visited)
            listOf("oneOf", "anyOf", "allOf").forEach { key ->
                node.path(key).values().forEachIndexed { i, _ -> walk(tree, "$target/$key/$i", visited) }
            }
        }

        val http: HttpClient =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        fun send(
            server: FakeLlmServer,
            method: String,
            path: String,
            body: String?,
            authorization: String?,
        ): HttpResponse<String> {
            val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            val request = HttpRequest.newBuilder(URI("${server.baseUrl}$path")).method(method, publisher).timeout(Duration.ofSeconds(15))
            if (body != null) request.header("content-type", "application/json")
            authorization?.let { request.header("authorization", it) }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun anthropic(
            prompt: String = "hi",
            system: String = "",
        ) = """{"model":"example-model","max_tokens":16$system,"messages":[{"role":"user","content":"$prompt"}]}"""

        const val OPEN_AI = """{"model":"example-model","messages":[{"role":"user","content":"hi"}]}"""
        const val GEMINI = """{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""
        const val INLINE = """{"name":"inline","steps":[{"text":"t"}]}"""

        fun callVendors(server: FakeLlmServer) {
            send(server, "POST", "/anthropic/v1/messages", anthropic(), null)
            send(server, "POST", "/anthropic/v1/messages", anthropic(system = ""","system":{"type":"text"}"""), null)
            send(server, "POST", "/openai/v1/chat/completions", OPEN_AI, null)
            send(server, "POST", "/gemini/v1beta/models/example-model:generateContent", GEMINI, null)
        }

        val control = Target.Operation("/control/scenario")
        val unauthorized = Target.Response("Unauthorized", "401")
        val noRoute = Target.Response("NoRoute", "404")

        val exchanges =
            listOf(
                Exchange("health, nothing loaded", "GET", "/health", Target.Operation("/health")),
                Exchange("health, lenient, with a scenario", "GET", "/health", Target.Operation("/health"), lenient = true, setup = {
                    it.engine.load("hello")
                }),
                Exchange("health with a token configured", "GET", "/health", Target.Operation("/health"), serverToken = TOKEN),
                Exchange(
                    "openapi",
                    "GET",
                    "/openapi",
                    Target.Operation("/openapi"),
                    serverToken = TOKEN,
                    assertBody = { assertEquals(specFile.readText(), it) },
                ),
                Exchange("load by name", "POST", "/control/scenario", control, """{"name":"hello"}""", expectStatus = 200),
                Exchange("load inline", "POST", "/control/scenario", control, """{"scenario":$INLINE}""", expectStatus = 200),
                Exchange(
                    "load with the token",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"name":"hello"}""",
                    authorization = "Bearer $TOKEN",
                    serverToken = TOKEN,
                    expectStatus = 200,
                ),
                Exchange(
                    "load an unknown name",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"name":"no-such-scenario"}""",
                    expectStatus = 400,
                ),
                Exchange(
                    "load both",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"name":"hello","scenario":$INLINE}""",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange("load neither", "POST", "/control/scenario", control, "{}", requestValid = false, expectStatus = 400),
                Exchange(
                    "load an extra field",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"name":"hello","extra":1}""",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange(
                    "load a non-string name",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"name":5}""",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange(
                    "load a blank name",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"name":" "}""",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange(
                    "load an illegal name",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"name":"../x"}""",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange("load a non-object", "POST", "/control/scenario", control, "[1]", requestValid = false, expectStatus = 400),
                Exchange("load malformed JSON", "POST", "/control/scenario", control, "{", requestValid = false, expectStatus = 400),
                Exchange("load an empty body", "POST", "/control/scenario", control, "", requestValid = false, expectStatus = 400),
                Exchange(
                    "load an inline scenario with no steps",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"scenario":{"name":"s","steps":[]}}""",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange(
                    "load an inline scenario with an unknown field",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"scenario":{"name":"s","steps":[{"text":"t"}],"extra":1}}""",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange(
                    "load an inline scenario with a bad error status",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"scenario":{"name":"s","steps":[{"error":{"status":42}}]}}""",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange(
                    "load without the token",
                    "POST",
                    "/control/scenario",
                    control,
                    """{"name":"hello"}""",
                    serverToken = TOKEN,
                    expectStatus = 401,
                ),
                Exchange("scenarios", "GET", "/control/scenarios", Target.Operation("/control/scenarios"), expectStatus = 200),
                Exchange(
                    "scenarios without the token",
                    "GET",
                    "/control/scenarios",
                    Target.Operation("/control/scenarios"),
                    serverToken = TOKEN,
                    expectStatus = 401,
                ),
                Exchange("reset", "POST", "/control/reset", Target.Operation("/control/reset"), expectStatus = 200),
                Exchange(
                    "reset with a wrong token",
                    "POST",
                    "/control/reset",
                    Target.Operation("/control/reset"),
                    authorization = "Bearer wrong",
                    serverToken = TOKEN,
                    expectStatus = 401,
                ),
                Exchange("calls, empty", "GET", "/control/calls", Target.Operation("/control/calls"), expectStatus = 200),
                Exchange(
                    "calls after strict no-scenario calls on every vendor",
                    "GET",
                    "/control/calls",
                    Target.Operation("/control/calls"),
                    expectStatus = 200,
                    setup = ::callVendors,
                    assertBody = { assertTrue(it.contains("\"no-scenario\"") && it.contains("\"unknown\""), it) },
                ),
                Exchange(
                    "calls after served, no-more-steps and lenient calls",
                    "GET",
                    "/control/calls",
                    Target.Operation("/control/calls"),
                    lenient = true,
                    expectStatus = 200,
                    setup = { server ->
                        callVendors(server)
                        server.engine.load("fail-then-retry")
                        callVendors(server)
                    },
                    assertBody = { assertTrue(it.contains("\"no-more-steps\"") && it.contains("\"error:500\""), it) },
                ),
                Exchange(
                    "calls without the token",
                    "GET",
                    "/control/calls",
                    Target.Operation("/control/calls"),
                    serverToken = TOKEN,
                    expectStatus = 401,
                ),
                Exchange("an unknown control path without the token", "GET", "/control/nope", unauthorized, serverToken = TOKEN),
                Exchange("an unknown path", "GET", "/nope", noRoute),
                Exchange("a known path with the wrong method", "GET", "/control/scenario", noRoute),
                Exchange("a trailing slash", "GET", "/health/", noRoute),
                Exchange(
                    "an unknown control path with the token",
                    "GET",
                    "/control/nope",
                    noRoute,
                    authorization = "Bearer $TOKEN",
                    serverToken = TOKEN,
                ),
                Exchange(
                    "anthropic, served by a step",
                    "POST",
                    "/anthropic/v1/messages",
                    Target.Operation("/anthropic/v1/messages"),
                    anthropic(),
                    expectStatus = 200,
                    setup = { it.engine.load("hello") },
                ),
                Exchange(
                    "anthropic, no scenario loaded",
                    "POST",
                    "/anthropic/v1/messages",
                    Target.Operation("/anthropic/v1/messages"),
                    anthropic(),
                    expectStatus = 400,
                    assertBody = { assertContains(it, "\"no scenario loaded\"") },
                ),
                Exchange(
                    "anthropic, after the last step",
                    "POST",
                    "/anthropic/v1/messages",
                    Target.Operation("/anthropic/v1/messages"),
                    anthropic(),
                    expectStatus = 400,
                    setup = { server ->
                        server.engine.load("hello")
                        send(server, "POST", "/anthropic/v1/messages", anthropic(), null)
                    },
                    assertBody = { assertContains(it, "\"no more steps\"") },
                ),
                Exchange(
                    "openai, served by a step",
                    "POST",
                    "/openai/v1/chat/completions",
                    Target.Operation("/openai/v1/chat/completions"),
                    OPEN_AI,
                    expectStatus = 200,
                    setup = { it.engine.load("hello") },
                ),
                Exchange(
                    "openai, no scenario loaded",
                    "POST",
                    "/openai/v1/chat/completions",
                    Target.Operation("/openai/v1/chat/completions"),
                    OPEN_AI,
                    expectStatus = 400,
                    assertBody = { assertContains(it, "\"no scenario loaded\"") },
                ),
                Exchange(
                    "openai, after the last step",
                    "POST",
                    "/openai/v1/chat/completions",
                    Target.Operation("/openai/v1/chat/completions"),
                    OPEN_AI,
                    expectStatus = 400,
                    setup = { server ->
                        server.engine.load("hello")
                        send(server, "POST", "/anthropic/v1/messages", anthropic(), null)
                    },
                    assertBody = { assertContains(it, "\"no more steps\"") },
                ),
                Exchange(
                    "gemini, served by a step",
                    "POST",
                    "/gemini/v1beta/models/example-model:generateContent",
                    Target.Operation("/gemini/{version}/models/{model}:generateContent"),
                    GEMINI,
                    expectStatus = 200,
                    setup = { it.engine.load("hello") },
                ),
                Exchange(
                    "gemini, no scenario loaded",
                    "POST",
                    "/gemini/v1beta/models/example-model:generateContent",
                    Target.Operation("/gemini/{version}/models/{model}:generateContent"),
                    GEMINI,
                    expectStatus = 400,
                    assertBody = { assertContains(it, "\"no scenario loaded\"") },
                ),
                Exchange(
                    "gemini, after the last step",
                    "POST",
                    "/gemini/v1beta/models/example-model:generateContent",
                    Target.Operation("/gemini/{version}/models/{model}:generateContent"),
                    GEMINI,
                    expectStatus = 400,
                    setup = { server ->
                        server.engine.load("hello")
                        send(server, "POST", "/anthropic/v1/messages", anthropic(), null)
                    },
                    assertBody = { assertContains(it, "\"no more steps\"") },
                ),
                Exchange(
                    "anthropic, streamed after the last step",
                    "POST",
                    "/anthropic/v1/messages",
                    Target.Operation("/anthropic/v1/messages"),
                    """{"model":"example-model","max_tokens":16,"stream":true,"messages":[{"role":"user","content":"hi"}]}""",
                    expectStatus = 400,
                    setup = { server ->
                        server.engine.load("hello")
                        send(server, "POST", "/anthropic/v1/messages", anthropic(), null)
                    },
                    assertBody = { assertContains(it, "\"no more steps\"") },
                ),
                Exchange(
                    "anthropic, lenient, after the last step",
                    "POST",
                    "/anthropic/v1/messages",
                    Target.Operation("/anthropic/v1/messages"),
                    anthropic(),
                    lenient = true,
                    expectStatus = 400,
                    setup = { server ->
                        server.engine.load("hello")
                        send(server, "POST", "/anthropic/v1/messages", anthropic(), null)
                    },
                    assertBody = { assertContains(it, "\"no more steps\"") },
                ),
                Exchange(
                    "anthropic, streamed with no scenario loaded",
                    "POST",
                    "/anthropic/v1/messages",
                    Target.Operation("/anthropic/v1/messages"),
                    """{"model":"example-model","max_tokens":16,"stream":true,"messages":[{"role":"user","content":"hi"}]}""",
                    expectStatus = 400,
                    assertBody = { assertContains(it, "\"no scenario loaded\"") },
                ),
                Exchange(
                    "anthropic, unparseable",
                    "POST",
                    "/anthropic/v1/messages",
                    Target.Operation("/anthropic/v1/messages"),
                    "{",
                    requestValid = false,
                    expectStatus = 400,
                ),
                Exchange(
                    "batches, list",
                    "GET",
                    "/anthropic/v1/messages/batches",
                    Target.Operation("/anthropic/v1/messages/batches"),
                    expectStatus = 501,
                ),
                Exchange(
                    "batches, create",
                    "POST",
                    "/anthropic/v1/messages/batches",
                    Target.Operation("/anthropic/v1/messages/batches"),
                    "{}",
                    expectStatus = 501,
                ),
                Exchange(
                    "batches, get one",
                    "GET",
                    "/anthropic/v1/messages/batches/abc",
                    Target.Operation("/anthropic/v1/messages/batches/{batchId}"),
                    expectStatus = 501,
                ),
            )

        /** Each must be rejected by both the Scenario schema and ScenarioLoader.decode. */
        val invalidScenarios =
            listOf(
                "no steps" to """{"name":"x","steps":[]}""",
                "no name" to """{"steps":[{"text":"t"}]}""",
                "an unknown field" to """{"name":"x","steps":[{"text":"t"}],"extra":1}""",
                "the removed rules format" to """{"name":"x","rules":[{"role":"r","respond":[{"text":"t"}]}]}""",
                "a null steps list" to """{"name":"x","steps":null}""",
                "a step with text and error" to """{"name":"x","steps":[{"text":"t","error":{"status":500}}]}""",
                "a step with neither" to """{"name":"x","steps":[{}]}""",
                "a step with both null" to """{"name":"x","steps":[{"text":null,"error":null}]}""",
                "thinking on an error step" to
                    """{"name":"x","steps":[{"thinking":"h","error":{"status":500}}]}""",
                "streamErrorAfterChunks on a text step" to
                    """{"name":"x","steps":[{"text":"t","streamErrorAfterChunks":2}]}""",
                "streamErrorAfterChunks of 0" to
                    """{"name":"x","steps":[{"error":{"status":500},"streamErrorAfterChunks":0}]}""",
                "streamChunkChars of 0" to """{"name":"x","steps":[{"text":"t","streamChunkChars":0}]}""",
                "streamChunkChars past Int" to
                    """{"name":"x","steps":[{"text":"t","streamChunkChars":3000000000}]}""",
                "a negative delay" to """{"name":"x","steps":[{"text":"t","streamInterChunkDelayMs":-1}]}""",
                "an unknown field in a step" to """{"name":"x","steps":[{"text":"t","extra":1}]}""",
                "an error status of 42" to """{"name":"x","steps":[{"error":{"status":42}}]}""",
                "an error status of 600" to """{"name":"x","steps":[{"error":{"status":600}}]}""",
                "an error with no status" to """{"name":"x","steps":[{"error":{"body":"b"}}]}""",
                "an unknown field in an error" to """{"name":"x","steps":[{"error":{"status":500,"extra":1}}]}""",
                "a null description" to """{"name":"x","description":null,"steps":[{"text":"t"}]}""",
                "a null stopReason" to """{"name":"x","steps":[{"text":"t","stopReason":null}]}""",
                "a null delay" to """{"name":"x","steps":[{"text":"t","streamInterChunkDelayMs":null}]}""",
                "a null error body" to """{"name":"x","steps":[{"error":{"status":500,"body":null}}]}""",
                "null error headers" to """{"name":"x","steps":[{"error":{"status":500,"headers":null}}]}""",
                "a non-string error header" to
                    """{"name":"x","steps":[{"error":{"status":500,"headers":{"a":1}}}]}""",
            )
    }
}
