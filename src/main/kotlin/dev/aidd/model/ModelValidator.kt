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
            if (model.schemaVersion !in setOf("1.0", "1.1")) {
                add(Diagnostic("UNSUPPORTED_SCHEMA_VERSION", "Expected schemaVersion 1.0 or 1.1"))
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
                if (model.schemaVersion == "1.0" && node.usesVersion11Feature()) {
                    add(
                        Diagnostic(
                            "FEATURE_REQUIRES_SCHEMA_1_1",
                            "Contract Model fields and expressions require schemaVersion 1.1",
                            node.id,
                        ),
                    )
                }
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
                if (model.schemaVersion == "1.1") {
                    addAll(validateContractNode(node, nodesById))
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

    private fun ModelNode.usesVersion11Feature(): Boolean =
        type in setOf("Parameter", "Result") ||
            valueType != null ||
            members.isNotEmpty() ||
            total != null ||
            listOf("accepts", "returns", "mayFailWith").any { relations[it].orEmpty().isNotEmpty() } ||
            expression.containsVersion11Expression()

    private fun com.fasterxml.jackson.databind.JsonNode?.containsVersion11Expression(): Boolean {
        if (this == null || !isObject) return false
        val version11Operations = setOf(
            "valueRef",
            "enumLiteral",
            "setLiteral",
            "listLiteral",
            "add",
            "sub",
            "mul",
            "size",
            "contains",
            "index",
            "union",
            "intersect",
            "difference",
            "append",
            "concat",
            "slice",
        )
        if (path("op").asText() in version11Operations) return true
        return path("args").takeIf { it.isArray }?.any { it.containsVersion11Expression() } == true ||
            path("domain").takeIf { it.isObject }.containsVersion11Expression() ||
            path("body").takeIf { it.isObject }.containsVersion11Expression()
    }

    private fun validateContractNode(
        node: ModelNode,
        nodesById: Map<String, ModelNode>,
    ): List<Diagnostic> = buildList {
        if (node.type in setOf("Parameter", "Result")) {
            if (node.valueType == null) {
                add(Diagnostic("MISSING_VALUE_TYPE", "${node.type} requires valueType", node.id))
            } else {
                addAll(validateValueType(node.id, node.valueType, nodesById))
            }
        } else if (node.valueType != null) {
            add(Diagnostic("UNEXPECTED_VALUE_TYPE", "valueType is only valid on Parameter or Result", node.id))
        }

        if (node.members.isNotEmpty()) {
            if (node.type != "Type") {
                add(Diagnostic("UNEXPECTED_ENUM_MEMBERS", "members is only valid on Type", node.id))
            } else {
                if (node.members.any(String::isBlank)) {
                    add(Diagnostic("INVALID_ENUM_MEMBERS", "Enum members must be non-empty", node.id))
                }
                if (node.members.distinct().size != node.members.size) {
                    add(Diagnostic("DUPLICATE_ENUM_MEMBER", "Enum members must be unique", node.id))
                }
            }
        }

        if (node.type == "Operation") {
            validateTargets(node, "accepts", "Parameter", nodesById, this)
            validateTargets(node, "returns", "Result", nodesById, this)
            validateTargets(node, "mayFailWith", "Error", nodesById, this)
            if (node.relations["returns"].orEmpty().size != 1) {
                add(Diagnostic("INVALID_OPERATION_RESULT", "Operation requires exactly one Result", node.id))
            }
            if (node.relations["accepts"].orEmpty().distinct().size != node.relations["accepts"].orEmpty().size) {
                add(Diagnostic("DUPLICATE_OPERATION_PARAMETER", "Operation accepts must not repeat a Parameter", node.id))
            }
        } else {
            listOf("accepts", "returns", "mayFailWith").forEach { relation ->
                if (node.relations[relation].orEmpty().isNotEmpty()) {
                    add(
                        Diagnostic(
                            "INVALID_RELATION_SOURCE",
                            "$relation is only valid on Operation",
                            node.id,
                        ),
                    )
                }
            }
        }

        if (node.type == "Contract") {
            if (node.total == null) {
                add(Diagnostic("MISSING_CONTRACT_TOTAL", "Contract requires total", node.id))
            }
            val definitions = node.relations["defines"].orEmpty()
            if (definitions.size != 1 || nodesById[definitions.singleOrNull()]?.type != "Operation") {
                add(
                    Diagnostic(
                        "INVALID_CONTRACT_OPERATION",
                        "Contract defines must contain exactly one Operation",
                        node.id,
                    ),
                )
            }
            val constrained = node.relations["constrains"].orEmpty().mapNotNull(nodesById::get)
            if (node.relations["constrains"].orEmpty().distinct().size !=
                node.relations["constrains"].orEmpty().size
            ) {
                add(
                    Diagnostic(
                        "DUPLICATE_CONTRACT_CONSTRAINT",
                        "Contract constrains must not repeat a claim",
                        node.id,
                    ),
                )
            }
            val invalidConstraints = constrained.filter {
                it.type !in setOf("Precondition", "Postcondition", "Error")
            }
            if (invalidConstraints.isNotEmpty()) {
                add(
                    Diagnostic(
                        "INVALID_CONTRACT_CONSTRAINT_TYPE",
                        "Contract constrains may target only Precondition, Postcondition, and Error",
                        node.id,
                    ),
                )
            }
            if (constrained.none { it.type == "Postcondition" }) {
                add(
                    Diagnostic(
                        "INVALID_CONTRACT_POSTCONDITION",
                        "Contract requires at least one Postcondition",
                        node.id,
                    ),
                )
            }
            val operation = definitions.singleOrNull()?.let(nodesById::get)
            val operationErrors = operation?.relations?.get("mayFailWith").orEmpty().toSet()
            val contractErrors = constrained.filter { it.type == "Error" }.map(ModelNode::id).toSet()
            if (operation != null && operationErrors != contractErrors) {
                add(
                    Diagnostic(
                        "CONTRACT_ERROR_MISMATCH",
                        "Contract Error constraints must equal the Operation mayFailWith set",
                        node.id,
                    ),
                )
            }
            if (operation?.type == "Operation") {
                val parameterIds = operation.relations["accepts"].orEmpty().toSet()
                val resultIds = operation.relations["returns"].orEmpty().toSet()
                constrained.forEach { constraint ->
                    val allowedReferences = if (constraint.type == "Postcondition") {
                        parameterIds + resultIds
                    } else {
                        parameterIds
                    }
                    val invalidReferences = constraint.expression.collectValueReferences() - allowedReferences
                    if (invalidReferences.isNotEmpty()) {
                        add(
                            Diagnostic(
                                "VALUE_REFERENCE_OUTSIDE_CONTRACT",
                                "${constraint.type} refers to values outside its Operation: " +
                                    invalidReferences.sorted().joinToString(),
                                constraint.id,
                            ),
                        )
                    }
                }
            }
        } else if (node.total != null) {
            add(Diagnostic("UNEXPECTED_CONTRACT_TOTAL", "total is only valid on Contract", node.id))
        }

        if (node.type in setOf("Precondition", "Postcondition", "Error") && node.expression == null) {
            add(Diagnostic("MISSING_CONTRACT_EXPRESSION", "${node.type} requires expression", node.id))
        }
    }

    private fun com.fasterxml.jackson.databind.JsonNode?.collectValueReferences(): Set<String> {
        if (this == null || !isObject) return emptySet()
        val current = if (path("op").asText() == "valueRef" && path("id").isTextual) {
            setOf(path("id").asText())
        } else {
            emptySet()
        }
        val arguments = path("args").takeIf { it.isArray }
            ?.flatMap { it.collectValueReferences() }
            .orEmpty()
        return current +
            arguments +
            path("domain").takeIf { it.isObject }.collectValueReferences() +
            path("body").takeIf { it.isObject }.collectValueReferences()
    }

    private fun validateTargets(
        node: ModelNode,
        relation: String,
        expectedType: String,
        nodesById: Map<String, ModelNode>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        node.relations[relation].orEmpty().forEach { targetId ->
            val target = nodesById[targetId] ?: return@forEach
            if (target.type != expectedType) {
                diagnostics += Diagnostic(
                    "INVALID_RELATION_TARGET_TYPE",
                    "$relation must target $expectedType",
                    node.id,
                )
            }
        }
    }

    private fun validateValueType(
        nodeId: String,
        valueType: ValueType,
        nodesById: Map<String, ModelNode>,
    ): List<Diagnostic> = buildList {
        when (valueType.kind) {
            ValueKind.INT, ValueKind.BOOL, ValueKind.STRING -> {
                if (valueType.typeId != null || valueType.elementType != null) {
                    add(
                        Diagnostic(
                            "INVALID_VALUE_TYPE",
                            "${valueType.kind.wireValue} must not define typeId or elementType",
                            nodeId,
                        ),
                    )
                }
            }
            ValueKind.ENUM -> {
                val enumType = valueType.typeId?.let(nodesById::get)
                if (enumType?.type != "Type" || enumType.members.isEmpty()) {
                    add(
                        Diagnostic(
                            "INVALID_ENUM_TYPE",
                            "enum typeId must target a Type with non-empty members",
                            nodeId,
                        ),
                    )
                }
                if (valueType.elementType != null) {
                    add(Diagnostic("INVALID_VALUE_TYPE", "enum must not define elementType", nodeId))
                }
            }
            ValueKind.SET, ValueKind.LIST -> {
                if (valueType.typeId != null) {
                    add(Diagnostic("INVALID_VALUE_TYPE", "collection must not define typeId", nodeId))
                }
                val elementType = valueType.elementType
                if (elementType == null) {
                    add(Diagnostic("INVALID_VALUE_TYPE", "collection requires elementType", nodeId))
                } else if (elementType.kind in setOf(ValueKind.SET, ValueKind.LIST)) {
                    add(
                        Diagnostic(
                            "NESTED_COLLECTION_UNSUPPORTED",
                            "Collection nesting is unsupported",
                            nodeId,
                        ),
                    )
                } else {
                    addAll(validateValueType(nodeId, elementType, nodesById))
                }
            }
        }
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
