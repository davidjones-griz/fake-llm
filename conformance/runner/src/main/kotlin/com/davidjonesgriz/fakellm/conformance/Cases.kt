package com.davidjonesgriz.fakellm.conformance

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/** The startup settings a case needs. Cases with equal settings share a server unless [fresh]. */
data class ServerSettings(
    val lenient: Boolean = false,
    val controlToken: String? = null,
    val fresh: Boolean = false,
) {
    /** The settings two cases must agree on to share a server. */
    val shared: ServerSettings get() = copy(fresh = false)

    override fun toString(): String =
        listOfNotNull(
            if (lenient) "lenient" else "strict",
            if (controlToken != null) "control token" else "no control token",
            "fresh".takeIf { fresh },
        ).joinToString(", ")
}

/** One request a case sends. [body] is sent as JSON; [rawBody], when set, byte for byte instead. */
data class CaseRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
    val rawBody: String? = null,
)

/** One expected SSE event: its name, and its data as a JSON value or, for a string, the raw text. */
data class ExpectedEvent(
    val event: String,
    val data: JsonElement,
)

/** What the server must return: [status], the listed [headers], and at most one of the body forms. */
data class ExpectedResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
    val rawBody: String? = null,
    val events: List<ExpectedEvent>? = null,
)

data class Exchange(
    val request: CaseRequest,
    val response: ExpectedResponse,
)

/**
 * One conformance case, read from [file]. [scenario] is an inline scenario object, a bundled
 * scenario name (a JSON string), or [JsonNull] for none.
 */
data class Case(
    val name: String,
    val description: String,
    val server: ServerSettings,
    val scenario: JsonElement,
    val exchanges: List<Exchange>,
    val file: Path,
    /** The file's path below the cases directory, with `/` separators, such as `steps/reset.json`. */
    val relativePath: String,
)

/** A case file that fails the schema or the loader's own checks. Every problem is listed. */
class InvalidCasesException(
    val problems: List<String>,
) : RuntimeException("invalid conformance cases:\n" + problems.joinToString("\n") { "  $it" })

/**
 * Reads every `*.json` file below a cases directory, in path order, and validates each against
 * the case schema before parsing it. Any invalid file, a name that differs from its file name, a
 * duplicate name, or no case files at all throws [InvalidCasesException] naming every problem.
 */
class CaseLoader(
    schemaFile: Path,
) {
    private val schema: Schema =
        SchemaRegistry
            .withDefaultDialect(Dialects.getDraft202012())
            .getSchema(schemaFile.readText(), InputFormat.JSON)

    fun load(casesDir: Path): List<Case> {
        require(Files.isDirectory(casesDir)) { "no cases directory at $casesDir" }
        val files =
            Files.walk(casesDir).use { paths ->
                paths.filter { it.isRegularFile() && it.extension == "json" }.sorted().toList()
            }
        val problems = mutableListOf<String>()
        if (files.isEmpty()) problems += "no case files below $casesDir"
        val cases =
            files.mapNotNull { file ->
                val relative = file.relativeTo(casesDir).invariantSeparatorsPathString
                val errors = schema.validate(file.readText(), InputFormat.JSON).map { it.toString() }
                if (errors.isNotEmpty()) {
                    problems += errors.map { "$relative: $it" }
                    null
                } else {
                    parse(Json.parseToJsonElement(file.readText()).jsonObject, file, relative).also {
                        if (it.name != file.nameWithoutExtension) problems += "$relative: name '${it.name}' must equal the file name"
                    }
                }
            }
        cases.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, dupes) ->
            problems += "case name '$name' is used by ${dupes.joinToString { it.relativePath }}"
        }
        if (problems.isNotEmpty()) throw InvalidCasesException(problems)
        return cases
    }

    private fun parse(
        obj: JsonObject,
        file: Path,
        relative: String,
    ): Case {
        val server = obj["server"]?.jsonObject
        return Case(
            name = obj.string("name")!!,
            description = obj.string("description")!!,
            server =
                ServerSettings(
                    lenient = server?.get("lenient")?.jsonPrimitive?.booleanOrNull ?: false,
                    controlToken = server?.string("controlToken"),
                    fresh = server?.get("fresh")?.jsonPrimitive?.booleanOrNull ?: false,
                ),
            scenario = obj.getValue("scenario"),
            exchanges = obj.getValue("exchanges").jsonArray.map { parseExchange(it.jsonObject) },
            file = file,
            relativePath = relative,
        )
    }

    private fun parseExchange(obj: JsonObject): Exchange {
        val request = obj.getValue("request").jsonObject
        val response = obj.getValue("response").jsonObject
        return Exchange(
            request =
                CaseRequest(
                    method = request.string("method")!!,
                    path = request.string("path")!!,
                    headers = request.stringMap("headers"),
                    body = request["body"],
                    rawBody = request.string("rawBody"),
                ),
            response =
                ExpectedResponse(
                    status = response.getValue("status").jsonPrimitive.int,
                    headers = response.stringMap("headers"),
                    body = response["body"],
                    rawBody = response.string("rawBody"),
                    events =
                        response["events"]?.jsonArray?.map {
                            ExpectedEvent(it.jsonObject.string("event")!!, it.jsonObject.getValue("data"))
                        },
                ),
        )
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.stringMap(key: String): Map<String, String> =
    get(key)?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
