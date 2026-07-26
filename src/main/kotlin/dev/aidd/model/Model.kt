package dev.aidd.model

import com.fasterxml.jackson.databind.JsonNode

data class AiddModel(
    val context: String,
    val schemaVersion: String,
    val specId: String,
    val nodes: List<ModelNode>,
)

data class ModelNode(
    val id: String,
    val type: String,
    val label: String,
    val status: ClaimStatus,
    val basis: ClaimBasis,
    val evidence: List<Evidence>,
    val relations: Map<String, List<String>>,
    val expression: JsonNode?,
    val generatedBy: String?,
    val valueType: ValueType? = null,
    val members: List<String> = emptyList(),
    val total: Boolean? = null,
)

data class ValueType(
    val kind: ValueKind,
    val typeId: String? = null,
    val elementType: ValueType? = null,
)

enum class ValueKind(val wireValue: String) {
    INT("int"),
    BOOL("bool"),
    STRING("string"),
    ENUM("enum"),
    SET("set"),
    LIST("list");

    companion object {
        fun fromWire(value: String): ValueKind =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported value type: $value")
    }
}

data class Evidence(
    val path: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val sha256: String,
)

enum class ClaimStatus(val wireValue: String) {
    CANDIDATE("candidate"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    companion object {
        fun fromWire(value: String): ClaimStatus =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported claim status: $value")
    }
}

enum class ClaimBasis(val wireValue: String) {
    STATED("stated"),
    OBSERVED("observed"),
    DERIVED("derived"),
    ASSUMED("assumed");

    companion object {
        fun fromWire(value: String): ClaimBasis =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported claim basis: $value")
    }
}

object Vocabulary {
    const val CONTEXT = "https://aidd.dev/context/v1"

    val nodeTypes = setOf(
        "Requirement",
        "Term",
        "Entity",
        "Type",
        "State",
        "Transition",
        "Operation",
        "Constraint",
        "Invariant",
        "Precondition",
        "Postcondition",
        "Error",
        "Assumption",
        "Example",
        "Counterexample",
        "CodeSymbol",
        "TestCase",
        "Contract",
        "Evidence",
        "HumanDecision",
        "Parameter",
        "Result",
    )

    val relations = setOf(
        "defines",
        "constrains",
        "derivesFrom",
        "dependsOn",
        "transitionsFrom",
        "transitionsTo",
        "implementedBy",
        "testedBy",
        "evidencedBy",
        "contradicts",
        "supersedes",
        "accepts",
        "returns",
        "mayFailWith",
    )
}
