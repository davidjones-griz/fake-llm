package com.davidjonesgriz.fakellm.conformance

import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.nio.file.Path
import java.util.stream.Stream

/**
 * Runs every case in `conformance/cases` against the server under test, one dynamic test per case.
 * The target comes from system properties, which the Gradle build sets from `-P` options:
 * - `fakellm.command`: the runner starts the server with this command (see [CommandServer]);
 * - `fakellm.baseUrl` (with `fakellm.controlToken` if the server has one): an already running server.
 * With neither, the cases are skipped with that reason; the case files are still validated, so a
 * malformed case fails the build. `fakellm.failOnSkip=true` turns every skip into a failure.
 */
class ConformanceSuite {
    @TestFactory
    fun cases(): Stream<DynamicNode> {
        val cases = CaseLoader(Path.of(property("fakellm.schema")!!)).load(Path.of(property("fakellm.casesDir")!!))
        val target =
            property("fakellm.command")?.let { Target.Command(it) }
                ?: property("fakellm.baseUrl")?.let { Target.BaseUrl(it, property("fakellm.controlToken")) }
                ?: return Stream.of(skip("no target", "set -Pfakellm.command=<command> or -Pfakellm.baseUrl=<url> to run the cases"))
        val plan = planRun(cases, target)
        val problem = listOfNotNull(plan.targetProblem?.let { dynamicTest("target") { fail(it) } })
        val groups = plan.groups.map { group(it) }
        val skipped = plan.skipped.map { (case, reason) -> skip(case.name, reason) }
        return (problem + groups + skipped).stream()
    }

    private fun group(group: CaseGroup): DynamicNode {
        val server = lazy { runCatching(group.server) }
        val tests =
            group.cases.map { case ->
                dynamicTest(case.name, case.file.toUri()) {
                    val handle = server.value.getOrThrow()
                    try {
                        CaseRunner(handle.baseUrl).run(case)
                    } catch (e: CaseFailedException) {
                        val output = handle.output()
                        if (output.isEmpty()) throw e
                        throw CaseFailedException("${e.message}\n  server output:\n$output", e)
                    }
                }
            }
        // JUnit closes the stream once the group's tests have run, so a failing case cannot leave
        // the server running for the rest of the run.
        return dynamicContainer(group.label, tests.stream().onClose { if (server.isInitialized()) server.value.getOrNull()?.close() })
    }

    private fun skip(
        name: String,
        reason: String,
    ): DynamicNode =
        dynamicTest(name) {
            if (property("fakellm.failOnSkip") == "true") fail("skipped, and fakellm.failOnSkip is set: $reason")
            abort<Unit>(reason)
        }

    private fun property(name: String): String? = System.getProperty(name)?.takeIf { it.isNotBlank() }
}
