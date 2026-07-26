package dev.aidd.extractor.kotlin

import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
    kotlin.system.exitProcess(runCli(args))
}

fun runCli(
    args: Array<String>,
    stdout: PrintStream = System.out,
    stderr: PrintStream = System.err,
): Int {
    val parsed = parseArguments(args)
    if (parsed is CliArguments.Help) {
        stdout.println(usage)
        return 0
    }
    if (parsed is CliArguments.Error) {
        stderr.println(parsed.message)
        stderr.println(usage)
        return 2
    }
    parsed as CliArguments.Valid

    return try {
        val result = KotlinExtractor().extract(parsed.repository, parsed.allowBuildTool)
        CodeFactsJson.writeTo(parsed.output, result)
        stdout.println(parsed.output.toAbsolutePath().normalize())
        if (result.diagnostics.any { it.severity in setOf("error", "UNSUPPORTED") }) 4 else 0
    } catch (exception: IllegalArgumentException) {
        stderr.println(exception.message)
        2
    } catch (exception: Exception) {
        stderr.println("Extraction failed: ${exception.message ?: exception::class.simpleName}")
        1
    }
}

private fun parseArguments(args: Array<String>): CliArguments {
    var repository: Path? = null
    var output: Path? = null
    var allowBuildTool = false
    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--repo" -> {
                if (index + 1 >= args.size) return CliArguments.Error("--repo requires a path")
                repository = Path(args[++index])
            }
            "--out" -> {
                if (index + 1 >= args.size) return CliArguments.Error("--out requires a path")
                output = Path(args[++index])
            }
            "--allow-build-tool" -> allowBuildTool = true
            "--help", "-h" -> return CliArguments.Help
            else -> return CliArguments.Error("Unknown option: $argument")
        }
        index += 1
    }
    return when {
        repository == null -> CliArguments.Error("--repo is required")
        output == null -> CliArguments.Error("--out is required")
        else -> CliArguments.Valid(repository, output, allowBuildTool)
    }
}

private sealed interface CliArguments {
    data class Valid(
        val repository: Path,
        val output: Path,
        val allowBuildTool: Boolean,
    ) : CliArguments

    data class Error(val message: String) : CliArguments

    data object Help : CliArguments
}

private const val usage =
    "Usage: aidd-kotlin-extractor --repo <path> --out <code-facts.json> [--allow-build-tool]"
