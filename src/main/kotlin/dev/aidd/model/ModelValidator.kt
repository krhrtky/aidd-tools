package dev.aidd.model

import java.nio.file.Files
import java.nio.file.Path

data class Diagnostic(
    val code: String,
    val message: String,
    val nodeId: String? = null,
    val severity: Severity = Severity.ERROR,
)

enum class Severity {
    ERROR,
    HUMAN_REVIEW,
    WARNING,
}

data class ValidationResult(
    val model: AiddModel?,
    val diagnostics: List<Diagnostic>,
) {
    val isValid: Boolean
        get() = model != null && diagnostics.none { it.severity == Severity.ERROR }

    val requiresHumanReview: Boolean
        get() = diagnostics.any { it.severity == Severity.HUMAN_REVIEW }
}

class ModelValidator(
    private val parser: ModelParser = ModelParser(),
) {
    fun validate(path: Path): ValidationResult {
        val model = try {
            parser.parse(path)
        } catch (exception: Exception) {
            return ValidationResult(
                model = null,
                diagnostics = listOf(
                    Diagnostic("INVALID_MODEL", exception.message ?: exception::class.java.simpleName),
                ),
            )
        }
        return validate(model, path.toAbsolutePath().parent)
    }

    fun validate(model: AiddModel, baseDirectory: Path? = null): ValidationResult {
        val diagnostics = buildList {
            if (model.context != Vocabulary.CONTEXT) {
                add(Diagnostic("INVALID_CONTEXT", "Only the embedded v1 context is allowed"))
            }
            if (model.schemaVersion != "1.0") {
                add(Diagnostic("UNSUPPORTED_SCHEMA_VERSION", "Expected schemaVersion 1.0"))
            }
            if (!model.specId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*"))) {
                add(Diagnostic("INVALID_SPEC_ID", "specId contains unsupported characters"))
            }

            val duplicateIds = model.nodes.groupingBy(ModelNode::id).eachCount().filterValues { it > 1 }.keys
            duplicateIds.sorted().forEach {
                add(Diagnostic("DUPLICATE_ID", "Duplicate graph node id: $it", it))
            }
            val nodesById = model.nodes.associateBy(ModelNode::id)
            val ids = nodesById.keys
            val humanApprovedIds = model.nodes
                .filter { it.type == "HumanDecision" && it.status == ClaimStatus.ACCEPTED }
                .flatMap { decision ->
                    decision.relations["defines"].orEmpty() + decision.relations["constrains"].orEmpty()
                }
                .toSet()
            model.nodes.forEach { node ->
                if (!node.id.startsWith("urn:aidd:")) {
                    add(Diagnostic("UNSTABLE_ID", "Node id must start with urn:aidd:", node.id))
                }
                if (node.type !in Vocabulary.nodeTypes) {
                    add(Diagnostic("UNKNOWN_NODE_TYPE", "Unknown node type: ${node.type}", node.id))
                }
                if (node.generatedBy != null && node.generatedBy !in setOf("llm", "human", "harness", "extractor")) {
                    add(Diagnostic("INVALID_GENERATOR", "generatedBy must be llm, human, harness, or extractor", node.id))
                }
                if (node.type == "HumanDecision" && node.status == ClaimStatus.ACCEPTED) {
                    if (node.generatedBy != "human" || node.evidence.isEmpty()) {
                        add(
                            Diagnostic(
                                "INVALID_HUMAN_DECISION",
                                "Accepted HumanDecision requires generatedBy=human and inline evidence",
                                node.id,
                                Severity.HUMAN_REVIEW,
                            ),
                        )
                    }
                }
                if (node.type == "Transition") {
                    listOf("transitionsFrom", "transitionsTo").forEach { relation ->
                        val targets = node.relations[relation].orEmpty()
                        if (targets.size != 1) {
                            add(
                                Diagnostic(
                                    "INVALID_TRANSITION_ENDPOINT",
                                    "Transition requires exactly one $relation state",
                                    node.id,
                                ),
                            )
                        } else if (nodesById[targets.single()]?.type != "State") {
                            add(
                                Diagnostic(
                                    "INVALID_TRANSITION_ENDPOINT_TYPE",
                                    "$relation must target a State",
                                    node.id,
                                ),
                            )
                        }
                    }
                }
                node.relations.toSortedMap().forEach { (relation, targets) ->
                    targets.sorted()
                        .filterNot(ids::contains)
                        .filterNot { relation == "evidencedBy" }
                        .forEach { target ->
                        add(
                            Diagnostic(
                                "DANGLING_REFERENCE",
                                "$relation refers to missing node: $target",
                                node.id,
                            ),
                        )
                    }
                }
                if (
                    node.status == ClaimStatus.ACCEPTED &&
                    node.basis == ClaimBasis.ASSUMED &&
                    node.id !in humanApprovedIds
                ) {
                    add(
                        Diagnostic(
                            "ACCEPTED_ASSUMPTION_REQUIRES_DECISION",
                            "An accepted assumption requires an explicit HumanDecision node",
                            node.id,
                            Severity.HUMAN_REVIEW,
                        ),
                    )
                }
                if (
                    node.status == ClaimStatus.ACCEPTED &&
                    node.generatedBy?.lowercase() == "llm" &&
                    node.id !in humanApprovedIds
                ) {
                    add(
                        Diagnostic(
                            "LLM_CLAIM_CANNOT_AUTO_ACCEPT",
                            "LLM-generated claims require a HumanDecision",
                            node.id,
                            Severity.HUMAN_REVIEW,
                        ),
                    )
                }
                val hasProvenance = node.evidence.isNotEmpty() ||
                    node.relations["derivesFrom"].orEmpty().isNotEmpty() ||
                    node.relations["evidencedBy"].orEmpty().isNotEmpty() ||
                    node.id in humanApprovedIds
                if (node.type !in setOf("Evidence", "HumanDecision") && !hasProvenance) {
                    add(
                        Diagnostic(
                            "MISSING_PROVENANCE_EVIDENCE",
                            "Claim requires inline evidence, derivesFrom, evidencedBy, or HumanDecision",
                            node.id,
                        ),
                    )
                }
                node.evidence.forEach { evidence ->
                    addAll(validateEvidence(node.id, evidence, baseDirectory))
                }
                if (node.expression != null) {
                    addAll(ExpressionValidator.validate(node, nodesById))
                }
            }
        }.sortedWith(compareBy(Diagnostic::code, { it.nodeId.orEmpty() }, Diagnostic::message))
        return ValidationResult(model, diagnostics)
    }

    private fun validateEvidence(
        nodeId: String,
        evidence: Evidence,
        baseDirectory: Path?,
    ): List<Diagnostic> {
        if (evidence.endLine < evidence.startLine ||
            (evidence.endLine == evidence.startLine && evidence.endColumn < evidence.startColumn)
        ) {
            return listOf(Diagnostic("INVALID_SOURCE_SPAN", "Evidence end precedes start", nodeId))
        }
        if (!evidence.sha256.matches(Regex("[a-f0-9]{64}"))) {
            return listOf(Diagnostic("INVALID_EVIDENCE_HASH", "Evidence SHA-256 is invalid", nodeId))
        }
        if (baseDirectory == null) {
            return emptyList()
        }
        val normalizedBase = baseDirectory.toAbsolutePath().normalize()
        val resolved = normalizedBase.resolve(evidence.path).normalize()
        if (!resolved.startsWith(normalizedBase)) {
            return listOf(Diagnostic("EVIDENCE_PATH_ESCAPE", "Evidence escapes the model directory", nodeId))
        }
        if (!Files.exists(resolved)) {
            return listOf(
                Diagnostic(
                    "EVIDENCE_NOT_FOUND",
                    "Evidence file does not exist: ${evidence.path}",
                    nodeId,
                ),
            )
        }
        val realBase = normalizedBase.toRealPath()
        val realResolved = resolved.toRealPath()
        if (!realResolved.startsWith(realBase) || Files.isSymbolicLink(resolved)) {
            return listOf(Diagnostic("EVIDENCE_PATH_ESCAPE", "Evidence uses a symlink or escapes the model directory", nodeId))
        }
        return if (Hashing.sha256(resolved) == evidence.sha256) {
            emptyList()
        } else {
            listOf(Diagnostic("EVIDENCE_HASH_MISMATCH", "Evidence content changed", nodeId))
        }
    }
}
