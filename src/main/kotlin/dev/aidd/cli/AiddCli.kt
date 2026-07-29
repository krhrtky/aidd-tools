package dev.aidd.cli

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aidd.alloy.AlloyCompiler
import dev.aidd.alloy.AlloyRunner
import dev.aidd.alloy.Bounds
import dev.aidd.alloy.ClaimSelection
import dev.aidd.alloy.VerificationResult
import dev.aidd.alloy.VerificationStatus
import dev.aidd.backport.BackportDiagnosticSeverity
import dev.aidd.backport.BackportService
import dev.aidd.generation.TypeScriptGenerator
import dev.aidd.generation.UnsupportedGenerationException
import dev.aidd.model.Hashing
import dev.aidd.model.ModelParser
import dev.aidd.model.ModelValidator
import dev.aidd.model.Severity
import dev.aidd.refinement.ObservedContractParser
import dev.aidd.refinement.RefinementCompiler
import dev.aidd.refinement.UnsupportedRefinementException
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
    private val acceptance: AcceptanceService = AcceptanceService(),
    private val observedContracts: ObservedContractParser = ObservedContractParser(),
    private val refinementCompiler: RefinementCompiler = RefinementCompiler(),
    private val typeScriptGenerator: TypeScriptGenerator = TypeScriptGenerator(),
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
        "explore" -> exploreModel(
            modelPath = args.requiredPath("--model"),
            boundsPath = args.optionalPath("--bounds"),
            outputDirectory = args.requiredPath("--out"),
            arguments = args.raw,
        )
        "accept" -> acceptModel(
            modelPath = args.requiredPath("--model"),
            decisionPath = args.requiredPath("--decision"),
            outputPath = args.requiredPath("--out"),
        )
        "generate" -> generateCode(
            modelPath = args.requiredPath("--model"),
            contractId = args.required("--contract"),
            language = args.required("--language"),
            outputPath = args.requiredPath("--out"),
        )
        "render" -> renderModel(args.requiredPath("--model"), args.requiredPath("--out"))
        else -> usage("Expected formalize validate, check, explore, accept, generate, render, or run")
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
            acceptedAlloyAlias = true,
        )
        "explore" -> exploreModel(
            modelPath = args.requiredPath("--model"),
            boundsPath = args.optionalPath("--bounds"),
            outputDirectory = args.requiredPath("--out"),
            arguments = args.raw,
            artifacts = ExplorationArtifacts.BACKPORT,
            auditFactsPath = args.optionalPath("--facts"),
        )
        "render" -> renderBackport(args)
        "diff" -> diffModels(
            observed = args.requiredPath("--model"),
            intended = args.requiredPath("--against"),
            output = args.requiredPath("--out"),
        )
        "refine" -> refineCode(
            factsPath = args.requiredPath("--facts"),
            modelPath = args.requiredPath("--model"),
            contractId = args.required("--contract"),
            operation = args.required("--operation"),
            boundsPath = args.optionalPath("--bounds"),
            outputDirectory = args.requiredPath("--out"),
            arguments = args.raw,
        )
        "extract" -> runExtractor(args)
        "run" -> runBackport(args)
        else -> usage("Expected backport extract, validate, check, explore, refine, render, diff, or run")
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
        acceptedAlloyAlias: Boolean = false,
        auditFactsPath: Path? = null,
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
        if (acceptedAlloyAlias) {
            SafePaths.writeText(safeOutputDirectory.resolve("accepted.als"), alloy)
        }
        JsonOutput.write(safeOutputDirectory.resolve("bounds.json"), bounds)
        val verification = runner.run(alloy, bounds, counterexamples)
        JsonOutput.write(safeOutputDirectory.resolve("verification.json"), verification)
        SafePaths.writeText(safeOutputDirectory.resolve("spec.md"), renderer.renderAccepted(model))
        writeManifest(
            safeOutputDirectory,
            targetModel,
            bounds,
            verification,
            arguments,
            model,
            acceptedAlloyAlias = acceptedAlloyAlias,
            auditFactsPath = auditFactsPath,
        )
        return if (verification.status == VerificationStatus.PROVISIONAL) {
            explorationExitCode(verification)
        } else {
            exitCode(verification.status)
        }
    }

    private fun exploreModel(
        modelPath: Path,
        boundsPath: Path?,
        outputDirectory: Path,
        arguments: List<String>,
        artifacts: ExplorationArtifacts = ExplorationArtifacts.FORMALIZE,
        auditFactsPath: Path? = null,
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
        val alloy = try {
            compiler.compile(model, bounds, ClaimSelection.ACCEPTED_AND_CANDIDATE)
        } catch (exception: Exception) {
            val message = exception.message ?: exception::class.java.simpleName
            SafePaths.writeText(
                safeOutputDirectory.resolve(artifacts.alloy),
                "// Candidate model could not be compiled: $message\n",
            )
            JsonOutput.write(safeOutputDirectory.resolve("bounds.json"), bounds)
            val unsupported = VerificationResult(
                status = VerificationStatus.UNSUPPORTED,
                boundedOutcome = null,
                scope = bounds,
                commands = emptyList(),
                diagnostics = listOf(message),
            )
            JsonOutput.write(safeOutputDirectory.resolve(artifacts.verification), unsupported)
            SafePaths.writeText(
                safeOutputDirectory.resolve(artifacts.markdown),
                artifacts.render(renderer, model),
            )
            writeManifest(
                outputDirectory = safeOutputDirectory,
                modelPath = targetModel,
                bounds = bounds,
                verification = unsupported,
                arguments = arguments,
                model = model,
                mode = "candidate-exploration",
                explorationArtifacts = artifacts,
                auditFactsPath = auditFactsPath,
            )
            return artifacts.unsupportedExitCode
        }
        SafePaths.writeText(safeOutputDirectory.resolve(artifacts.alloy), alloy)
        JsonOutput.write(safeOutputDirectory.resolve("bounds.json"), bounds)
        val verification = runner.run(alloy, bounds, counterexamples, forceProvisional = true)
        JsonOutput.write(safeOutputDirectory.resolve(artifacts.verification), verification)
        SafePaths.writeText(
            safeOutputDirectory.resolve(artifacts.markdown),
            artifacts.render(renderer, model),
        )
        writeManifest(
            outputDirectory = safeOutputDirectory,
            modelPath = targetModel,
            bounds = bounds,
            verification = verification,
            arguments = arguments,
            model = model,
            mode = "candidate-exploration",
            explorationArtifacts = artifacts,
            auditFactsPath = auditFactsPath,
        )
        return if (artifacts == ExplorationArtifacts.BACKPORT) {
            backportExplorationExitCode(verification)
        } else {
            explorationExitCode(verification)
        }
    }

    private fun renderModel(modelPath: Path, output: Path): Int {
        val validation = validator.validate(modelPath)
        if (!validation.isValid) {
            return validateModel(modelPath)
        }
        SafePaths.writeText(output, renderer.renderAccepted(validation.model!!))
        return if (validation.requiresHumanReview) 5 else 0
    }

    private fun renderBackport(args: Arguments): Int {
        val output = args.requiredPath("--out")
        val facts = args.optionalPath("--facts")
        if (facts != null) {
            if (args.optionalPath("--model") != null || args.optional("--view") != null) {
                throw CliException("render accepts either --facts or --model with --view", 2)
            }
            return renderFacts(facts, output)
        }
        val modelPath = args.requiredPath("--model")
        val view = args.required("--view")
        val validation = validator.validate(modelPath)
        if (!validation.isValid) {
            return validateModel(modelPath)
        }
        val rendered = when (view) {
            "accepted" -> renderer.renderAccepted(validation.model!!)
            "candidate-business" -> renderer.renderCandidateBusiness(validation.model!!)
            else -> throw CliException("--view must be accepted or candidate-business", 2)
        }
        SafePaths.writeText(output, rendered)
        return if (validation.requiresHumanReview) 5 else 0
    }

    private fun acceptModel(
        modelPath: Path,
        decisionPath: Path,
        outputPath: Path,
    ): Int {
        val result = acceptance.accept(modelPath, decisionPath, outputPath)
        val output = JsonOutput.mapper.createObjectNode().apply {
            put("accepted", true)
            put("model", result.modelPath.toString())
            put("manifest", result.manifestPath.toString())
        }
        println(JsonOutput.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output))
        return 0
    }

    private fun generateCode(
        modelPath: Path,
        contractId: String,
        language: String,
        outputPath: Path,
    ): Int {
        if (language != "typescript") {
            throw CliException("Reference generator supports only typescript", 4)
        }
        val validation = validator.validate(modelPath)
        if (!validation.isValid || validation.requiresHumanReview) {
            return validateModel(modelPath)
        }
        val code = try {
            typeScriptGenerator.generate(validation.model!!, contractId)
        } catch (exception: UnsupportedGenerationException) {
            throw CliException(exception.message ?: "Unsupported generation", 4)
        }
        SafePaths.writeText(outputPath, code)
        return 0
    }

    private fun validateBackport(facts: Path, model: Path, repository: Path? = null): Int {
        val modelCode = validateModel(model)
        if (modelCode != 0) {
            return modelCode
        }
        val diagnostics = backport.validateFactsDetailed(facts, model, repository)
        val json = JsonOutput.mapper.createObjectNode().apply {
            put("valid", diagnostics.isEmpty())
            putArray("diagnostics").also { array ->
                diagnostics.forEach { array.add(it.message) }
            }
            putArray("diagnosticDetails").also { array ->
                diagnostics.forEach { diagnostic ->
                    array.addObject().apply {
                        put("code", diagnostic.code)
                        put("severity", diagnostic.severity.name)
                        put("message", diagnostic.message)
                        diagnostic.nodeId?.let { put("nodeId", it) }
                    }
                }
            }
        }
        println(JsonOutput.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json))
        return when {
            diagnostics.isEmpty() -> 0
            diagnostics.any { it.severity == BackportDiagnosticSeverity.ERROR } -> 2
            else -> 3
        }
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

    private fun refineCode(
        factsPath: Path,
        modelPath: Path,
        contractId: String,
        operation: String,
        boundsPath: Path?,
        outputDirectory: Path,
        arguments: List<String>,
    ): Int {
        val validation = validator.validate(modelPath)
        if (!validation.isValid || validation.requiresHumanReview) {
            return validateModel(modelPath)
        }
        val factDiagnostics = backport.validateFacts(factsPath, modelPath)
        if (factDiagnostics.isNotEmpty()) {
            factDiagnostics.forEach(System.err::println)
            return 2
        }
        val bounds = boundsPath?.let(Bounds::read) ?: Bounds.defaultExploration()
        val output = SafePaths.directory(outputDirectory)
        val counterexamples = output.resolve("counterexamples")
        SafePaths.resetGeneratedDirectory(counterexamples)
        val targetModel = output.resolve("model.jsonld")
        val targetFacts = output.resolve("code-facts.json")
        if (modelPath.toAbsolutePath().normalize() != targetModel.toAbsolutePath().normalize()) {
            SafePaths.copy(modelPath, targetModel)
        }
        if (factsPath.toAbsolutePath().normalize() != targetFacts.toAbsolutePath().normalize()) {
            SafePaths.copy(factsPath, targetFacts)
        }
        val observed = try {
            observedContracts.parse(factsPath, operation)
        } catch (exception: Exception) {
            return writeUnsupportedRefinement(
                output,
                bounds,
                exception.message ?: exception::class.java.simpleName,
            )
        }
        val alloy = try {
            refinementCompiler.compile(validation.model!!, observed, contractId, bounds)
        } catch (exception: UnsupportedRefinementException) {
            return writeUnsupportedRefinement(output, bounds, exception.message ?: "Unsupported refinement")
        }
        SafePaths.writeText(output.resolve("refinement.als"), alloy)
        JsonOutput.write(output.resolve("bounds.json"), bounds)
        val verification = runner.run(alloy, bounds, counterexamples)
        JsonOutput.write(output.resolve("refinement.json"), verification)
        writeRefinementManifest(
            output = output,
            modelPath = targetModel,
            factsPath = targetFacts,
            contractId = contractId,
            operation = operation,
            bounds = bounds,
            verification = verification,
            arguments = arguments,
        )
        return if (verification.status == VerificationStatus.PROVISIONAL) {
            explorationExitCode(verification)
        } else {
            exitCode(verification.status)
        }
    }

    private fun writeUnsupportedRefinement(
        output: Path,
        bounds: Bounds,
        diagnostic: String,
    ): Int {
        val result = VerificationResult(
            status = VerificationStatus.UNSUPPORTED,
            boundedOutcome = null,
            scope = bounds,
            commands = emptyList(),
            diagnostics = listOf(diagnostic),
        )
        SafePaths.writeText(output.resolve("refinement.als"), "// UNSUPPORTED: $diagnostic\n")
        JsonOutput.write(output.resolve("bounds.json"), bounds)
        JsonOutput.write(output.resolve("refinement.json"), result)
        return 4
    }

    private fun writeRefinementManifest(
        output: Path,
        modelPath: Path,
        factsPath: Path,
        contractId: String,
        operation: String,
        bounds: Bounds,
        verification: VerificationResult,
        arguments: List<String>,
    ) {
        val generated = listOf("model.jsonld", "code-facts.json", "refinement.als", "bounds.json", "refinement.json")
        val hashes = generated.associateWith { Hashing.sha256(output.resolve(it)) }
        val manifest = JsonOutput.mapper.createObjectNode().apply {
            put("schemaVersion", "1.0")
            put("toolVersion", "0.1.0")
            put("mode", "code-refinement")
            put("inputModelSha256", Hashing.sha256(modelPath))
            put("inputCodeFactsSha256", Hashing.sha256(factsPath))
            put("contractId", contractId)
            put("operation", operation)
            set<ObjectNode>("bounds", JsonOutput.mapper.valueToTree(bounds))
            put("status", verification.status.name)
            putArray("arguments").also { array -> arguments.forEach(array::add) }
            set<ObjectNode>("outputSha256", JsonOutput.mapper.valueToTree(hashes))
        }
        JsonOutput.writeNode(output.resolve("manifest.json"), manifest)
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
            acceptedAlloyAlias = true,
            auditFactsPath = facts,
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
        mode: String = "accepted-verification",
        explorationArtifacts: ExplorationArtifacts = ExplorationArtifacts.FORMALIZE,
        acceptedAlloyAlias: Boolean = false,
        auditFactsPath: Path? = null,
    ) {
        val outputHashes = linkedMapOf<String, String>()
        val renderedSpecification = if (mode == "candidate-exploration") {
            explorationArtifacts.markdown
        } else {
            "spec.md"
        }
        val generated = mutableListOf(
            "model.jsonld",
            if (mode == "candidate-exploration") explorationArtifacts.alloy else "model.als",
            "bounds.json",
            if (mode == "candidate-exploration") explorationArtifacts.verification else "verification.json",
            renderedSpecification,
        )
        if (acceptedAlloyAlias) {
            generated += "accepted.als"
        }
        listOf("code-facts.json", "as-built.md")
            .filter { outputDirectory.resolve(it).exists() }
            .forEach(generated::add)
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
            put("mode", mode)
            put("command", arguments.take(2).joinToString(" "))
            put("inputModelSha256", Hashing.sha256(modelPath))
            auditFactsPath?.let { factsPath ->
                put("inputCodeFactsSha256", Hashing.sha256(factsPath))
                val extractor = JsonOutput.mapper.readTree(factsPath.toFile()).path("extractor")
                if (extractor.isObject) {
                    set<com.fasterxml.jackson.databind.JsonNode>("extractor", extractor)
                }
            }
            set<ObjectNode>("bounds", JsonOutput.mapper.valueToTree(bounds))
            put("status", verification.status.name)
            verification.boundedOutcome?.let { put("boundedOutcome", it.name) }
            put("boundsApproval", if (bounds.approved) "APPROVED" else "UNAPPROVED")
            putArray("arguments").also { array -> arguments.forEach(array::add) }
            putArray("diagnostics").also { array ->
                verification.diagnostics.sorted().forEach { message ->
                    array.addObject().apply {
                        put(
                            "code",
                            when (verification.status) {
                                VerificationStatus.UNSUPPORTED -> "UNSUPPORTED"
                                VerificationStatus.TIMEOUT -> "TIMEOUT"
                                else -> "VERIFICATION_DIAGNOSTIC"
                            },
                        )
                        put(
                            "severity",
                            if (verification.status in setOf(
                                    VerificationStatus.UNSUPPORTED,
                                    VerificationStatus.TIMEOUT,
                                )
                            ) {
                                "ERROR"
                            } else {
                                "WARNING"
                            },
                        )
                        put("message", message)
                    }
                }
            }
            putArray("assumptions").also { array ->
                model.nodes
                    .filter {
                        it.type == "Assumption" &&
                            (mode != "candidate-exploration" ||
                                it.status != dev.aidd.model.ClaimStatus.REJECTED)
                    }
                    .sortedBy { it.id }
                    .forEach { array.add(it.label) }
            }
            if (mode == "candidate-exploration") {
                putArray("targetClaimIds").also { array ->
                    model.nodes
                        .filter { it.status != dev.aidd.model.ClaimStatus.REJECTED }
                        .sortedBy { it.id }
                        .forEach { array.add(it.id) }
                }
                putObject("claimStatusCounts").also { counts ->
                    dev.aidd.model.ClaimStatus.entries
                        .sortedBy { it.wireValue }
                        .forEach { status ->
                            counts.put(
                                status.wireValue,
                                model.nodes.count { it.status == status },
                            )
                        }
                }
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

    private fun explorationExitCode(verification: VerificationResult): Int = when (verification.status) {
        VerificationStatus.TIMEOUT,
        VerificationStatus.UNSUPPORTED,
        -> 4
        VerificationStatus.HUMAN_REVIEW_REQUIRED -> 5
        else -> when (verification.boundedOutcome) {
            VerificationStatus.COUNTEREXAMPLE,
            VerificationStatus.NO_INSTANCE_WITHIN_SCOPE,
            -> 2
            VerificationStatus.TIMEOUT,
            VerificationStatus.UNSUPPORTED,
            -> 4
            VerificationStatus.HUMAN_REVIEW_REQUIRED -> 5
            else -> 3
        }
    }

    private fun backportExplorationExitCode(verification: VerificationResult): Int =
        if (
            verification.status in setOf(VerificationStatus.TIMEOUT, VerificationStatus.UNSUPPORTED) ||
            verification.boundedOutcome in setOf(VerificationStatus.TIMEOUT, VerificationStatus.UNSUPPORTED)
        ) {
            3
        } else if (
            verification.status == VerificationStatus.HUMAN_REVIEW_REQUIRED ||
            verification.boundedOutcome == VerificationStatus.HUMAN_REVIEW_REQUIRED
        ) {
            5
        } else {
            0
        }

    private fun usage(message: String): Nothing = throw CliException(message, 2)
}

private enum class ExplorationArtifacts(
    val alloy: String,
    val verification: String,
    val markdown: String,
    val unsupportedExitCode: Int,
) {
    FORMALIZE(
        alloy = "model.als",
        verification = "verification.json",
        markdown = "candidate-spec.md",
        unsupportedExitCode = 4,
    ),
    BACKPORT(
        alloy = "candidate.als",
        verification = "exploration.json",
        markdown = "candidate-prose.md",
        unsupportedExitCode = 3,
    ),
    ;

    fun render(renderer: SpecRenderer, model: dev.aidd.model.AiddModel): String =
        if (this == BACKPORT) {
            renderer.renderCandidateBusiness(model)
        } else {
            renderer.renderCandidates(model)
        }
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
