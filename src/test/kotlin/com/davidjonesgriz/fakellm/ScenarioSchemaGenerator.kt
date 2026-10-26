package com.davidjonesgriz.fakellm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File

/**
 * Writes the scenario JSON Schema, `schema/scenario.schema.json`, from the OpenAPI spec's
 * `Scenario` schema and the component schemas it references. The spec is the source; the file is
 * committed so that editors can fetch it by URL. Run with `./gradlew updateScenarioSchema`;
 * ScenarioSchemaTest fails when the committed file differs from what the spec gives.
 */
object ScenarioSchemaGenerator {
    const val ID = "https://raw.githubusercontent.com/davidjones-griz/fake-llm/main/schema/scenario.schema.json"
    const val SPEC_PATH = "openapi/fake-llm.yaml"
    const val SCHEMA_PATH = "schema/scenario.schema.json"

    private const val COMPONENT_PREFIX = "#/components/schemas/"
    private const val ROOT = "Scenario"

    /**
     * Keywords the generated file must not contain: OpenAPI's own, which are not JSON Schema, and
     * 2020-12 keywords that editors support poorly.
     */
    private val DISALLOWED =
        setOf("discriminator", "xml", "externalDocs", "example", "unevaluatedProperties", "prefixItems", "\$dynamicRef")

    /** Keywords whose value maps names to schemas, so the names are not keywords themselves. */
    private val SCHEMA_MAPS = setOf("properties", "patternProperties", "\$defs")

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    /** The schema file's text, from the spec's YAML text. */
    fun generate(specYaml: String): String {
        val spec =
            Json.parseToJsonElement(
                YAMLMapper
                    .builder()
                    .build()
                    .readTree(specYaml)
                    .toString(),
            ) as JsonObject
        val components = (spec["components"] as? JsonObject)?.get("schemas") as? JsonObject ?: error("the spec has no components/schemas")
        val collected = linkedMapOf<String, JsonElement>()
        val pending = ArrayDeque(listOf(ROOT))
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name in collected) continue
            val schema = components[name] ?: error("the spec has no component schema '$name'")
            collected[name] = rewrite(schema, "$COMPONENT_PREFIX$name") { pending += it }
        }
        val root = collected.remove(ROOT) as JsonObject
        val document =
            buildJsonObject {
                put("\$schema", JsonPrimitive("https://json-schema.org/draft/2020-12/schema"))
                put("\$id", JsonPrimitive(ID))
                put("\$comment", JsonPrimitive("Generated from $SPEC_PATH by ./gradlew updateScenarioSchema. Do not edit by hand."))
                put("title", JsonPrimitive("fake-llm scenario"))
                root.forEach { (key, value) -> put(key, value) }
                put("\$defs", JsonObject(collected))
            }
        return pretty.encodeToString(JsonElement.serializer(), document) + "\n"
    }

    /** [schema] with component references turned into `$defs` references; [found] gets each name referenced. */
    private fun rewrite(
        schema: JsonElement,
        where: String,
        found: (String) -> Unit,
    ): JsonElement =
        when (schema) {
            is JsonArray -> {
                JsonArray(schema.mapIndexed { i, item -> rewrite(item, "$where/$i", found) })
            }

            is JsonObject -> {
                JsonObject(
                    schema.mapValues { (key, value) ->
                        check(key !in DISALLOWED) { "$where: '$key' cannot go in the scenario JSON Schema" }
                        when {
                            key == "\$ref" -> {
                                JsonPrimitive(defsRef(value, where, found))
                            }

                            key in SCHEMA_MAPS && value is JsonObject -> {
                                JsonObject(
                                    value.mapValues { (n, s) ->
                                        rewrite(s, "$where/$key/$n", found)
                                    },
                                )
                            }

                            else -> {
                                rewrite(value, "$where/$key", found)
                            }
                        }
                    },
                )
            }

            else -> {
                schema
            }
        }

    private fun defsRef(
        ref: JsonElement,
        where: String,
        found: (String) -> Unit,
    ): String {
        val target = (ref as? JsonPrimitive)?.takeIf { it.isString }?.content
        check(target != null && target.startsWith(COMPONENT_PREFIX)) { "$where: only component schema references are supported, got $ref" }
        val name = target.removePrefix(COMPONENT_PREFIX)
        if (name == ROOT) return "#"
        found(name)
        return "#/\$defs/$name"
    }
}

/** `./gradlew updateScenarioSchema`: writes [ScenarioSchemaGenerator.SCHEMA_PATH] from the spec. */
fun main() {
    val text = ScenarioSchemaGenerator.generate(File(ScenarioSchemaGenerator.SPEC_PATH).readText())
    File(ScenarioSchemaGenerator.SCHEMA_PATH).apply { parentFile.mkdirs() }.writeText(text)
    println("wrote ${ScenarioSchemaGenerator.SCHEMA_PATH}")
}
