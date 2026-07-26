package dev.aidd.cli

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aidd.alloy.AlloyCompiler
import dev.aidd.alloy.AlloyRunner
import dev.aidd.alloy.Bounds
import dev.aidd.alloy.VerificationResult
import dev.aidd.alloy.VerificationStatus
import dev.aidd.backport.BackportService
import dev.aidd.model.Hashing
import dev.aidd.model.ModelParser
import dev.aidd.model.ModelValidator
import dev.aidd.model.Severity
import dev.aidd.render.SpecRenderer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class AiddCli(
    private val validator: ModelValidator = ModelValidator(),
    private val parser: ModelParser = ModelParser(),
    private val compiler: AlloyCompiler = AlloyCompiler(),
    private val runner: AlloyRunner = AlloyRunner(),
    private val renderer: SpecRenderer = SpecRenderer(),
    private val backport: BackportService = BackportService(),
) {
    fun execute(arguments: List<String>): Int = try {
        val args = Arguments(arguments)
        when (args.positional(0)) {
            "formalize" -> executeFormalize(args)
            "backport" -> executeBackport(args)
            else -> usage("First argument must be formalize or backport")
        }
    } catch (exception: CliException) {
        System.err.println(exception.message)
        exception.exitCode
    } catch (exception: Exception) {
        System.err.println(exception.message ?: exception::class.java.simpleName)
        2
    }

    private fun executeFormalize(args: Arguments): Int = when (args.positional(1)) {
        "validate" -> validateModel(args.requiredPath("--model"))
        "check", "run" -> checkModel(
            modelPath = args.requiredPath("--model"),
            boundsPath = args.optionalPath("--bounds"),
            outputDirectory = args.requiredPath("--out"),
            arguments = args.raw,
        )
        "render" -> renderModel(args.requiredPath("--model"), args.requiredPath("--out"))
        else -> usage("Expected formalize validate, check, render, or run")
    }

    private fun executeBackport(args: Arguments): Int = when (args.positional(1)) {
        "validate" -> validateBackport(
            args.requiredPath("--facts"),
            args.requiredPath("--model"),
            args.optionalPath("--repo"),
        )
        "check" -> checkModel(
            modelPath = args.requiredPath("--model"),
            boundsPath = args.optionalPath("--bounds"),
            outputDirectory = args.requiredPath("--out"),
            arguments = args.raw,
        )
        "render" -> renderFacts(args.requiredPath("--facts"), args.requiredPath("--out"))
        "diff" -> diffModels(
            observed = args.requiredPath("--model"),
            intended = args.requiredPath("--against"),
            output = args.requiredPath("--out"),
        )
        "extract" -> runExtractor(args)
        "run" -> runBackport(args)
        else -> usage("Expected backport extract, validate, check, render, diff, or run")
    }

    private fun validateModel(modelPath: Path): Int {
        val result = validator.validate(modelPath)
        val json = JsonOutput.mapper.createObjectNode().apply {
            put("valid", result.isValid)
            put("requiresHumanReview", result.requiresHumanReview)
            val diagnostics = putArray("diagnostics")
            result.diagnostics.forEach { diagnostic ->
                diagnostics.addObject().apply {
                    put("code", diagnostic.code)
                    put("severity", diagnostic.severity.name)
                    put("message", diagnostic.message)
                    diagnostic.nodeId?.let { put("nodeId", it) }
                }
            }
        }
        println(JsonOutput.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json))
        return when {
            !result.isValid -> 2
            result.requiresHumanReview -> 5
            else -> 0
        }
    }

    private fun checkModel(
        modelPath: Path,
        boundsPath: Path?,
        outputDirectory: Path,
        arguments: List<String>,
    ): Int {
        val validation = validator.validate(modelPath)
        if (!validation.isValid) {
            return validateModel(modelPath)
        }
        if (validation.requiresHumanReview) {
            validateModel(modelPath)
            return 5
        }
        val model = validation.model!!
        val bounds = boundsPath?.let(Bounds::read) ?: Bounds.defaultExploration()
        val safeOutputDirectory = SafePaths.directory(outputDirectory)
        val counterexamples = safeOutputDirectory.resolve("counterexamples")
        SafePaths.resetGeneratedDirectory(counterexamples)
        val targetModel = safeOutputDirectory.resolve("model.jsonld")
        if (modelPath.toAbsolutePath().normalize() != targetModel.toAbsolutePath().normalize()) {
            SafePaths.copy(modelPath, targetModel)
        }
        val alloy = compiler.compile(model, bounds)
        SafePaths.writeText(safeOutputDirectory.resolve("model.als"), alloy)
        JsonOutput.write(safeOutputDirectory.resolve("bounds.json"), bounds)
        val verification = runner.run(alloy, bounds, counterexamples)
        JsonOutput.write(safeOutputDirectory.resolve("verification.json"), verification)
        SafePaths.writeText(safeOutputDirectory.resolve("spec.md"), renderer.renderAccepted(model))
        writeManifest(safeOutputDirectory, targetModel, bounds, verification, arguments, model)
        return exitCode(verification.status)
    }

    private fun renderModel(modelPath: Path, output: Path): Int {
        val validation = validator.validate(modelPath)
        if (!validation.isValid) {
            return validateModel(modelPath)
        }
        SafePaths.writeText(output, renderer.renderAccepted(validation.model!!))
        return if (validation.requiresHumanReview) 5 else 0
    }

    private fun validateBackport(facts: Path, model: Path, repository: Path? = null): Int {
        val modelCode = validateModel(model)
        if (modelCode != 0) {
            return modelCode
        }
        val diagnostics = backport.validateFacts(facts, model, repository)
        val json = JsonOutput.mapper.createObjectNode().apply {
            put("valid", diagnostics.isEmpty())
            putArray("diagnostics").also { array -> diagnostics.forEach(array::add) }
        }
        println(JsonOutput.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json))
        return if (diagnostics.isEmpty()) 0 else 2
    }

    private fun renderFacts(facts: Path, output: Path): Int {
        SafePaths.writeText(output, backport.renderFacts(facts))
        return 0
    }

    private fun diffModels(observed: Path, intended: Path, output: Path): Int {
        val result = backport.diff(observed, intended)
        JsonOutput.writeNode(output, backport.diffAsJson(result))
        return if (
            result.missing.isEmpty() &&
            result.extra.isEmpty() &&
            result.contradictions.isEmpty() &&
            result.evidenceMissing.isEmpty()
        ) 0 else 5
    }

    private fun runBackport(args: Arguments): Int {
        val extractCode = runExtractor(args)
        if (extractCode != 0) {
            return extractCode
        }
        val facts = args.requiredPath("--out").resolve("code-facts.json")
        renderFacts(facts, args.requiredPath("--out").resolve("as-built.md"))
        val model = args.optionalPath("--model") ?: return 0
        val validateCode = validateBackport(facts, model, args.requiredPath("--repo"))
        if (validateCode != 0) {
            return validateCode
        }
        return checkModel(
            model,
            args.optionalPath("--bounds"),
            args.requiredPath("--out"),
            args.raw,
        )
    }

    private fun runExtractor(args: Arguments): Int {
        val language = args.required("--language")
        if (language == "kotlin" && args.values("--contracts").isNotEmpty()) {
            throw CliException(
                "Kotlin contract ingestion is not implemented in v1; contracts were not ignored",
                4,
            )
        }
        val repo = args.requiredPath("--repo").toAbsolutePath().normalize()
        val out = args.requiredPath("--out").toAbsolutePath().normalize()
        val outputFile = if (out.fileName.toString().endsWith(".json")) {
            SafePaths.output(out)
        } else {
            SafePaths.directory(out).resolve("code-facts.json").also(SafePaths::output)
        }
        val configuredRoot = System.getenv("AIDD_TOOLS_HOME")
            ?: throw CliException("AIDD_TOOLS_HOME is required for extractor execution", 4)
        val root = Path.of(configuredRoot)
            .toAbsolutePath()
            .normalize()
        val command = when (language) {
            "typescript" -> mutableListOf(
                "node",
                root.resolve("extractors/typescript/dist/cli.js").toString(),
                "--repo",
                repo.toString(),
                "--out",
                outputFile.toString(),
            )
            "kotlin" -> mutableListOf<String>().apply {
                addAll(
                    listOf(
                        root.resolve(
                            "extractors/kotlin/build/install/aidd-kotlin-extractor/bin/aidd-kotlin-extractor",
                        ).toString(),
                        "--repo",
                        repo.toString(),
                        "--out",
                        outputFile.toString(),
                    ),
                )
                if (args.has("--allow-build-tool")) add("--allow-build-tool")
            }
            else -> throw CliException("Unsupported language: $language", 4)
        }
        args.values("--contracts").forEach {
            if (language == "typescript") {
                command.addAll(listOf("--contracts", Path.of(it).toAbsolutePath().normalize().toString()))
            }
        }
        val requiredArtifact = if (language == "kotlin") Path.of(command.first()) else Path.of(command[1])
        if (!requiredArtifact.exists()) {
            throw CliException(
                "$language extractor is not built",
                4,
            )
        }
        val process = ProcessBuilder(command)
            .directory(root.toFile())
            .inheritIO()
            .start()
        val processCode = process.waitFor()
        if (processCode != 0) return 4
        val factsRoot = JsonOutput.mapper.readTree(outputFile.toFile())
        val unsupported = factsRoot.path("diagnostics")
            .takeIf { it.isArray }
            ?.any {
                it.path("severity").asText(it.path("status").asText()).lowercase() in
                    setOf("error", "unsupported")
            } == true
        return if (unsupported) 4 else 0
    }

    private fun writeManifest(
        outputDirectory: Path,
        modelPath: Path,
        bounds: Bounds,
        verification: VerificationResult,
        arguments: List<String>,
        model: dev.aidd.model.AiddModel,
    ) {
        val outputHashes = linkedMapOf<String, String>()
        val generated = mutableListOf("model.jsonld", "model.als", "bounds.json", "verification.json", "spec.md")
        Files.list(outputDirectory.resolve("counterexamples")).use { entries ->
            generated += entries
                .filter { Files.isRegularFile(it) }
                .map { "counterexamples/${it.fileName}" }
                .sorted()
                .toList()
        }
        generated.forEach { name ->
            val path = outputDirectory.resolve(name)
            if (path.exists()) outputHashes[name] = Hashing.sha256(path)
        }
        val manifest = JsonOutput.mapper.createObjectNode().apply {
            put("schemaVersion", "1.0")
            put("toolVersion", "0.1.0")
            put("alloyVersion", "6.2.0")
            put("inputModelSha256", Hashing.sha256(modelPath))
            set<ObjectNode>("bounds", JsonOutput.mapper.valueToTree(bounds))
            put("status", verification.status.name)
            putArray("arguments").also { array -> arguments.forEach(array::add) }
            putArray("assumptions").also { array ->
                model.nodes.filter { it.type == "Assumption" }.sortedBy { it.id }.forEach { array.add(it.label) }
            }
            set<ObjectNode>("outputSha256", JsonOutput.mapper.valueToTree(outputHashes))
        }
        JsonOutput.writeNode(outputDirectory.resolve("manifest.json"), manifest)
    }

    private fun exitCode(status: VerificationStatus): Int = when (status) {
        VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE -> 0
        VerificationStatus.COUNTEREXAMPLE,
        VerificationStatus.NO_INSTANCE_WITHIN_SCOPE,
        -> 2
        VerificationStatus.PROVISIONAL -> 3
        VerificationStatus.TIMEOUT,
        VerificationStatus.UNSUPPORTED,
        -> 4
        VerificationStatus.HUMAN_REVIEW_REQUIRED -> 5
    }

    private fun usage(message: String): Nothing = throw CliException(message, 2)
}

private class CliException(message: String, val exitCode: Int) : RuntimeException(message)

private class Arguments(val raw: List<String>) {
    private val positionals = buildList {
        var index = 0
        while (index < raw.size) {
            val value = raw[index]
            if (!value.startsWith("--")) {
                add(value)
                index += 1
            } else if (value in flagOptions) {
                index += 1
            } else {
                index += 2
            }
        }
    }

    fun positional(index: Int): String? = positionals.getOrNull(index)

    fun required(name: String): String =
        optional(name) ?: throw CliException("Missing required option $name", 2)

    fun requiredPath(name: String): Path = Path.of(required(name))

    fun optionalPath(name: String): Path? = optional(name)?.let(Path::of)

    fun optional(name: String): String? {
        val index = raw.indexOf(name)
        if (index < 0 || index + 1 >= raw.size || raw[index + 1].startsWith("--")) return null
        return raw[index + 1]
    }

    fun values(name: String): List<String> = raw.mapIndexedNotNull { index, value ->
        if (value == name && index + 1 < raw.size) raw[index + 1] else null
    }

    fun has(name: String): Boolean = name in raw

    companion object {
        private val flagOptions = setOf("--allow-build-tool")
    }
}
