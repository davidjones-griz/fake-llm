package com.davidjonesgriz.fakellm

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * `fake-llm validate <file|dir>...`: checks scenario files with the checks a load by name makes,
 * without starting a server. A directory means its `*.json` files, not its subdirectories. Every
 * file is checked: `ok <path>` goes to [out] for a valid one, and `<path>: <problem>` to [err] for
 * an invalid one, followed by a summary on [out].
 *
 * Returns the exit code: 0 when every file is valid, 1 when any is invalid, and 2 for a usage
 * error (no paths, a path that does not exist, a directory with no `.json` files, or a flag), in
 * which case no file is checked.
 */
internal fun validateScenarios(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val files =
        try {
            scenarioFiles(args)
        } catch (e: ValidateUsageException) {
            err.println("fake-llm validate: ${e.message}\nUsage: fake-llm validate <file|dir>...")
            return EXIT_USAGE
        }
    val invalid =
        files.count { file ->
            val problem = scenarioFileProblem(file)
            if (problem == null) out.println("ok $file") else err.println("$file: $problem")
            problem != null
        }
    out.println("${files.size} ${if (files.size == 1) "file" else "files"}: ${files.size - invalid} valid, $invalid invalid")
    return if (invalid == 0) 0 else EXIT_INVALID
}

private class ValidateUsageException(
    message: String,
) : RuntimeException(message)

/** The files [args] name, in order, each directory's `*.json` files sorted by name. */
private fun scenarioFiles(args: List<String>): List<Path> {
    val problem = if (args.isEmpty()) "no files or directories given" else args.firstNotNullOfOrNull(::usageProblem)
    if (problem != null) throw ValidateUsageException(problem)
    return args.map { Path.of(it) }.flatMap { if (it.isDirectory()) jsonFiles(it) else listOf(it) }
}

/** Why [arg] is not a scenario file or a directory with some, or null when it is one. */
private fun usageProblem(arg: String): String? {
    val path = runCatching { Path.of(arg) }.getOrElse { e -> return "invalid path '$arg': ${(e as? InvalidPathException)?.reason ?: e}" }
    return when {
        arg.startsWith("-") -> "unknown flag '$arg'"
        path.isDirectory() -> "no .json files in $arg".takeIf { jsonFiles(path).isEmpty() }
        path.isRegularFile() -> null
        else -> "no such file or directory: $arg"
    }
}

private fun jsonFiles(dir: Path): List<Path> =
    Files.list(dir).use { entries ->
        entries.filter { it.isRegularFile() && it.extension == "json" }.sorted().toList()
    }

/** Why [file] would not load by name, or null when it would. */
private fun scenarioFileProblem(file: Path): String? =
    try {
        ScenarioLoader.decodeFile(file.nameWithoutExtension, file.readText())
        null
    } catch (e: IllegalArgumentException) {
        e.message
    } catch (e: IllegalStateException) {
        e.message
    } catch (e: IOException) {
        "cannot read the file: $e"
    }

private const val EXIT_INVALID = 1
private const val EXIT_USAGE = 2
