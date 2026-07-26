package dev.aidd.render

import com.fasterxml.jackson.databind.JsonNode
import dev.aidd.model.AiddModel
import dev.aidd.model.ClaimStatus
import dev.aidd.model.ModelNode

class SpecRenderer {
    fun renderAccepted(model: AiddModel): String =
        render(model, ClaimStatus.ACCEPTED, "As-built / Accepted Specification")

    fun renderCandidates(model: AiddModel): String =
        render(model, ClaimStatus.CANDIDATE, "Candidate Specification")

    private fun render(
        model: AiddModel,
        status: ClaimStatus,
        title: String,
    ): String = buildString {
        appendLine("# $title: ${model.specId}")
        appendLine()
        appendLine("> Generated deterministically from model.jsonld. Status: `${status.wireValue}`.")
        val nodes = model.nodes.filter { it.status == status }.sortedWith(
            compareBy(ModelNode::type, ModelNode::id),
        )
        if (nodes.isEmpty()) {
            appendLine()
            appendLine("No ${status.wireValue} claims.")
        }
        nodes.groupBy(ModelNode::type).toSortedMap().forEach { (type, typedNodes) ->
            appendLine()
            appendLine("## $type")
            typedNodes.forEach { node ->
                appendLine()
                appendLine("- **${node.label}** (`${node.id}`)")
                appendLine("  - Basis: `${node.basis.wireValue}`")
                node.expression?.let {
                    appendLine("  - Constraint: `${renderExpression(it)}`")
                }
                node.relations.toSortedMap().forEach { (relation, targets) ->
                    appendLine("  - $relation: ${targets.sorted().joinToString { "`$it`" }}")
                }
                node.evidence.sortedBy { it.path }.forEach { evidence ->
                    appendLine(
                        "  - Evidence: `${evidence.path}:${evidence.startLine}:${evidence.startColumn}` " +
                            "SHA-256 `${evidence.sha256}`",
                    )
                }
            }
        }
    }

    private fun renderExpression(expression: JsonNode): String {
        val op = expression.path("op").asText()
        return when (op) {
            "literal" -> expression.get("value").asText()
            "ref" -> expression.path("id").asText()
            else -> "$op(${expression.path("args").joinToString { renderExpression(it) }})"
        }
    }
}

