package com.davidjonesgriz.fakellm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    private val buildVersion =
        File("gradle.properties").readLines().first { it.startsWith("version=") }.removePrefix("version=")

    @Test
    fun `the bundled version is the build's version`() {
        assertEquals(buildVersion, fakeLlmVersion)
    }

    @Test
    fun `health reports the version, and the client reads it`() {
        withServer { client, _ ->
            val body = Json.parseToJsonElement(client.get("/health").body()).jsonObject
            assertEquals(buildVersion, body.getValue("version").jsonPrimitive.content)
            FakeLlmClient("http://127.0.0.1:${client.port}").use { assertEquals(buildVersion, it.health().version) }
        }
    }
}
