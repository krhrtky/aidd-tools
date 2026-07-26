package dev.aidd.alloy

import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import kodkod.engine.satlab.SATFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class VerificationStatus {
    NO_COUNTEREXAMPLE_WITHIN_SCOPE,
    COUNTEREXAMPLE,
    NO_INSTANCE_WITHIN_SCOPE,
    PROVISIONAL,
    TIMEOUT,
    UNSUPPORTED,
    HUMAN_REVIEW_REQUIRED,
}

data class CommandOutcome(
    val label: String,
    val kind: String,
    val satisfiable: Boolean,
    val artifact: String?,
)

data class VerificationResult(
    val status: VerificationStatus,
    val boundedOutcome: VerificationStatus?,
    val scope: Bounds,
    val commands: List<CommandOutcome>,
    val diagnostics: List<String>,
)

class AlloyRunner(
    private val timeoutSeconds: Long = 60,
) {
    init {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    }

    fun run(
        alloy: String,
        bounds: Bounds,
        artifactDirectory: Path,
        forceProvisional: Boolean = false,
    ): VerificationResult {
        Files.createDirectories(artifactDirectory)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "aidd-alloy-runner").apply { isDaemon = true }
        }
        val future = executor.submit<VerificationResult> {
            execute(alloy, bounds, artifactDirectory, forceProvisional)
        }
        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            VerificationResult(
                status = VerificationStatus.TIMEOUT,
                boundedOutcome = null,
                scope = bounds,
                commands = emptyList(),
                diagnostics = listOf("Alloy analysis exceeded ${timeoutSeconds}s"),
            )
        } catch (exception: Exception) {
            VerificationResult(
                status = VerificationStatus.UNSUPPORTED,
                boundedOutcome = null,
                scope = bounds,
                commands = emptyList(),
                diagnostics = listOf(rootCauseMessage(exception)),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun execute(
        alloy: String,
        bounds: Bounds,
        artifactDirectory: Path,
        forceProvisional: Boolean,
    ): VerificationResult {
        val reporter = A4Reporter.NOP
        val module = CompUtil.parseEverything_fromString(reporter, alloy)
        val options = A4Options().apply {
            solver = SATFactory.DEFAULT
            symmetry = 20
            noOverflow = true
        }
        val outcomes = module.allCommands.mapIndexed { index, command ->
            val solution = TranslateAlloyToKodkod.execute_command(
                reporter,
                module.allReachableSigs,
                command,
                options,
            )
            val isCounterexample = command.check && solution.satisfiable()
            val artifact = if (solution.satisfiable()) {
                val name = if (isCounterexample) "counterexample-${index + 1}.xml" else "instance-${index + 1}.xml"
                val target = artifactDirectory.resolve(name)
                solution.writeXML(target.toString(), module.allFunc)
                name
            } else {
                null
            }
            CommandOutcome(
                label = command.label.ifBlank { "command-${index + 1}" },
                kind = if (command.check) "check" else "run",
                satisfiable = solution.satisfiable(),
                artifact = artifact,
            )
        }
        val runOutcomes = outcomes.filter { it.kind == "run" }
        val checkOutcomes = outcomes.filter { it.kind == "check" }
        val boundedOutcome = when {
            outcomes.any { it.kind == "run" && !it.satisfiable } -> VerificationStatus.NO_INSTANCE_WITHIN_SCOPE
            checkOutcomes.any(CommandOutcome::satisfiable) -> VerificationStatus.COUNTEREXAMPLE
            checkOutcomes.isNotEmpty() && runOutcomes.any(CommandOutcome::satisfiable) ->
                VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE
            else -> VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE
        }
        val hasVerificationClaim = checkOutcomes.isNotEmpty()
        val diagnostics = if (hasVerificationClaim || boundedOutcome == VerificationStatus.NO_INSTANCE_WITHIN_SCOPE) {
            emptyList()
        } else {
            listOf("SATISFIABILITY_ONLY: no Alloy assertion was checked")
        }
        val reportedBoundedOutcome = if (
            forceProvisional ||
            hasVerificationClaim ||
            boundedOutcome == VerificationStatus.NO_INSTANCE_WITHIN_SCOPE
        ) {
            boundedOutcome
        } else {
            null
        }
        return VerificationResult(
            status = if (forceProvisional) {
                VerificationStatus.PROVISIONAL
            } else if (
                bounds.approved &&
                (hasVerificationClaim || boundedOutcome == VerificationStatus.NO_INSTANCE_WITHIN_SCOPE)
            ) {
                boundedOutcome
            } else {
                VerificationStatus.PROVISIONAL
            },
            boundedOutcome = reportedBoundedOutcome,
            scope = bounds,
            commands = outcomes,
            diagnostics = diagnostics,
        )
    }

    private fun rootCauseMessage(exception: Throwable): String {
        var cause = exception
        while (cause.cause != null) {
            cause = cause.cause!!
        }
        return cause.message ?: cause::class.java.simpleName
    }
}
