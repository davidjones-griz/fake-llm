package com.davidjonesgriz.fakellm

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The scenario JSON Schema, schema/scenario.schema.json: it is what the spec gives, it is a valid
 * 2020-12 schema on its own, it agrees with the server's decoder, and the spec agrees with the
 * Kotlin model it describes.
 */
class ScenarioSchemaTest {
    private val specText = File(ScenarioSchemaGenerator.SPEC_PATH).readText()
    private val schemaText = File(ScenarioSchemaGenerator.SCHEMA_PATH).readText()

    /** The spec as JSON, so its component schemas can be read with kotlinx. */
    private val spec =
        Json
            .parseToJsonElement(
                YAMLMapper
                    .builder()
                    .build()
                    .readTree(specText)
                    .toString(),
            ).jsonObject
    private val components =
        spec
            .getValue("components")
            .jsonObject
            .getValue("schemas")
            .jsonObject

    private val registry =
        SchemaRegistry.withDefaultDialect(Dialects.getDraft202012()) {
            it.schemas(
                mapOf(ScenarioSchemaGenerator.ID to schemaText),
            )
        }
    private val schema = registry.getSchema(SchemaLocation.of(ScenarioSchemaGenerator.ID))

    private fun errors(scenario: String): List<String> = schema.validate(scenario, InputFormat.JSON).map { it.toString() }

    @Test
    fun `the committed schema is the one the spec gives`() {
        assertEquals(
            ScenarioSchemaGenerator.generate(specText),
            schemaText,
            "${ScenarioSchemaGenerator.SCHEMA_PATH} is out of date: run ./gradlew updateScenarioSchema",
        )
    }

    @Test
    fun `the schema is a valid 2020-12 schema with an id`() {
        val metaSchema = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"))
        assertEquals(emptyList(), metaSchema.validate(schemaText, InputFormat.JSON).map { it.toString() })
        assertTrue(metaSchema.validate("""{"type":5}""", InputFormat.JSON).isNotEmpty(), "the meta-schema check is vacuous")
        val document = Json.parseToJsonElement(schemaText).jsonObject
        assertEquals(ScenarioSchemaGenerator.ID, document.getValue("\$id").jsonPrimitive.content)
        assertEquals("https://json-schema.org/draft/2020-12/schema", document.getValue("\$schema").jsonPrimitive.content)
    }

    @Test
    fun `every scenario file in the repository conforms to the schema`() {
        repositoryScenarioFiles().forEach { file -> assertEquals(emptyList(), errors(file.readText()), file.path) }
    }

    @Test
    fun `the schema rejects the scenarios the server's decoder rejects`() {
        invalidScenarios.forEach { (why, body) -> assertTrue(errors(body).isNotEmpty(), "schema accepted: $why") }
    }

    @Test
    fun `the schema accepts a top-level $schema and explicit nulls on nullable fields`() {
        val valid =
            """{"${'$'}schema":"${ScenarioSchemaGenerator.ID}","name":"x","steps":[
              {"text":null,"thinking":null,"error":{"status":500}},{"text":"t","error":null,"streamErrorAfterChunks":null},
              {"text":"t","toolCalls":null},{"toolCalls":[{"name":"f","arguments":null,"rawArguments":null,"id":null}]}
            ]}"""
        assertEquals(emptyList(), errors(valid))
    }

    @Test
    fun `the generator refuses what does not belong in the schema`() {
        fun specWithStep(step: String) =
            """
            components:
              schemas:
                Scenario:
                  type: object
                  properties:
                    steps:
                      items:
                        ${'$'}ref: "#/components/schemas/Step"
                Step: $step
            """.trimIndent()
        assertContains(
            assertFailsWith<IllegalStateException> {
                ScenarioSchemaGenerator.generate(specWithStep("{discriminator: {propertyName: kind}}"))
            }.message!!,
            "'discriminator' cannot go in the scenario JSON Schema",
        )
        assertContains(
            assertFailsWith<IllegalStateException> {
                ScenarioSchemaGenerator.generate(
                    specWithStep("{${'$'}ref: \"other.yaml#/X\"}"),
                )
            }.message!!,
            "only component schema references are supported",
        )
        assertContains(
            assertFailsWith<IllegalStateException> {
                ScenarioSchemaGenerator.generate(specWithStep("{${'$'}ref: \"#/components/schemas/Nope\"}"))
            }.message!!,
            "no component schema 'Nope'",
        )
        // A property may be named like a keyword: only keywords are refused.
        val named = ScenarioSchemaGenerator.generate(specWithStep("{properties: {example: {type: string}}}"))
        assertContains(named, "\"example\"")
    }

    @Test
    fun `the spec describes every field of the Kotlin model, with its type, nullability and whether it is required`() {
        assertEquals(emptyList(), modelDrift(components))
    }

    @Test
    fun `the drift check notices a field missing from the spec, and a changed type, nullability or requirement`() {
        fun doctored(
            schema: String,
            change: (JsonObject) -> JsonObject,
        ) = JsonObject(components + (schema to change(components.getValue(schema).jsonObject)))

        fun JsonObject.withProperties(change: (JsonObject) -> JsonObject) =
            JsonObject(this + ("properties" to change(getValue("properties").jsonObject)))

        val missing = doctored("Step") { step -> step.withProperties { JsonObject(it - "thinking") } }
        assertContains(modelDrift(missing).joinToString(), "Step: properties")
        val retyped =
            doctored("ErrorStep") { e ->
                e.withProperties {
                    JsonObject(it + ("status" to Json.parseToJsonElement("""{"type":"string"}""")))
                }
            }
        assertContains(modelDrift(retyped).joinToString(), "ErrorStep.status: the model has integer, the spec string")
        val nullable =
            doctored("Scenario") { s ->
                s.withProperties {
                    JsonObject(it + ("name" to Json.parseToJsonElement("""{"type":["string","null"]}""")))
                }
            }
        assertContains(modelDrift(nullable).joinToString(), "Scenario.name: nullable in the model false, in the spec true")
        val required = doctored("Scenario") { s -> JsonObject(s + ("required" to JsonArray(listOf(JsonPrimitive("name"))))) }
        assertContains(modelDrift(required).joinToString(), "Scenario: required")
    }

    /**
     * How the spec's component [schemas] differ from the model's serial descriptors, as readable
     * lines; empty when they agree. The `$schema` property is the spec's only addition: the loader
     * removes it before decoding.
     */
    private fun modelDrift(schemas: JsonObject): List<String> {
        val models =
            listOf(
                Scenario.serializer().descriptor,
                Step.serializer().descriptor,
                ToolCall.serializer().descriptor,
                ErrorStep.serializer().descriptor,
            )
        return models.flatMap { descriptor ->
            val name = descriptor.serialName.substringAfterLast('.')
            val schema = schemas.getValue(name).jsonObject
            val properties = schema.getValue("properties").jsonObject - "\$schema"
            val elements = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
            val required =
                schema["required"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
                    .toSet()
            val modelRequired = elements.filterIndexed { i, _ -> !descriptor.isElementOptional(i) }.toSet()
            listOfNotNull(
                "$name: properties ${properties.keys.sorted()}, the model has ${elements.sorted()}".takeIf {
                    properties.keys !=
                        elements.toSet()
                },
                "$name: required $required, the model requires $modelRequired".takeIf { required != modelRequired },
            ) +
                elements.withIndex().filter { it.value in properties }.flatMap { (i, element) ->
                    elementDrift("$name.$element", descriptor.getElementDescriptor(i), properties.getValue(element), schemas)
                }
        }
    }

    private fun elementDrift(
        where: String,
        model: SerialDescriptor,
        property: JsonElement,
        schemas: JsonObject,
    ): List<String> {
        val (types, nullable) = jsonTypes(property, schemas)
        val modelType =
            MODEL_TYPES[model.kind] ?: return listOf("$where: the model's kind ${model.kind} is not one the shape check supports")
        return listOfNotNull(
            "$where: the model has $modelType, the spec ${types.joinToString()}".takeIf { types != setOf(modelType) },
            "$where: nullable in the model ${model.isNullable}, in the spec $nullable".takeIf { model.isNullable != nullable },
        )
    }

    /** The non-null JSON types a property schema allows, and whether it allows null, following `$ref` and `oneOf`. */
    private fun jsonTypes(
        property: JsonElement,
        schemas: JsonObject,
    ): Pair<Set<String>, Boolean> {
        val obj = property.jsonObject
        val ref = obj["\$ref"]?.jsonPrimitive?.content
        val type = obj["type"]
        val allowed =
            when {
                ref != null -> {
                    listOf(
                        schemas
                            .getValue(ref.substringAfterLast('/'))
                            .jsonObject
                            .getValue("type")
                            .jsonPrimitive.content,
                    )
                }

                type is JsonPrimitive -> {
                    listOf(type.content)
                }

                type is JsonArray -> {
                    type.map { it.jsonPrimitive.content }
                }

                else -> {
                    obj.getValue("oneOf").jsonArray.flatMap {
                        jsonTypes(it, schemas).let { (t, n) ->
                            t +
                                (if (n) setOf("null") else emptySet())
                        }
                    }
                }
            }
        return (allowed.toSet() - "null") to ("null" in allowed)
    }

    private companion object {
        /** The JSON type of each descriptor kind the model uses; the shape check supports exactly these. */
        val MODEL_TYPES: Map<SerialKind, String> =
            mapOf(
                PrimitiveKind.STRING to "string",
                PrimitiveKind.INT to "integer",
                PrimitiveKind.LONG to "integer",
                StructureKind.CLASS to "object",
                StructureKind.LIST to "array",
                StructureKind.MAP to "object",
            )
    }
}
