package com.davidjonesgriz.fakellm.junit.fixtures

import com.davidjonesgriz.fakellm.FakeLlmServer
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import java.lang.annotation.Inherited
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/** Set by the EngineTestKit runs in FakeLlmExtensionTest; fixtures run only when it is present. */
const val FIXTURES_PARAMETER = "fakellm.fixtures"

/**
 * Marks a fixture class: a test class that FakeLlmExtensionTest runs through the EngineTestKit and
 * checks the outcome of. Anywhere else (an IDE running every class, say) the class is disabled
 * before any extension callback runs, so the deliberately failing fixtures stay quiet.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@ExtendWith(OnlyInTestKit::class)
annotation class Fixture

class OnlyInTestKit : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (context.getConfigurationParameter(FIXTURES_PARAMETER).isPresent) {
            ConditionEvaluationResult.enabled("run by FakeLlmExtensionTest")
        } else {
            ConditionEvaluationResult.disabled("a fixture for FakeLlmExtensionTest")
        }
}

/** What the fixtures observed, keyed by fixture and test, for FakeLlmExtensionTest to check. */
object Sink {
    private val values = ConcurrentHashMap<String, MutableList<Any?>>()

    /** Counted down by tests that must be running at the same moment. */
    @Volatile
    @JvmStatic
    var latch: CountDownLatch = CountDownLatch(0)

    @JvmStatic
    fun add(
        key: String,
        value: Any?,
    ) {
        values.computeIfAbsent(key) { Collections.synchronizedList(mutableListOf()) }.add(value)
    }

    @JvmStatic
    fun get(key: String): List<Any?> = values[key]?.toList() ?: emptyList()

    fun clear() {
        values.clear()
        latch = CountDownLatch(0)
    }
}

private val http: HttpClient =
    HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

/** Sends one Anthropic-style request to [server] and returns the status code. */
fun post(
    server: FakeLlmServer,
    model: String = "example-model",
): Int {
    val body = """{"model":"$model","max_tokens":16,"messages":[{"role":"user","content":"hi"}]}"""
    val request =
        HttpRequest
            .newBuilder(URI.create("${server.baseUrl}/anthropic/v1/messages"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
}
