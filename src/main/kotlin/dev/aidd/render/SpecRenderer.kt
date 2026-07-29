package dev.aidd.render

import com.fasterxml.jackson.databind.JsonNode
import dev.aidd.model.AiddModel
import dev.aidd.model.ClaimStatus
import dev.aidd.model.ModelNode
import dev.aidd.model.ValueKind
import dev.aidd.model.ValueType

class SpecRenderer {
    fun renderAccepted(model: AiddModel): String =
        render(model, ClaimStatus.ACCEPTED, "As-built / Accepted Specification")

    fun renderCandidates(model: AiddModel): String =
        render(model, ClaimStatus.CANDIDATE, "Candidate Specification")

    fun renderCandidateBusiness(model: AiddModel): String = buildString {
        appendLine("# 業務仕様候補: ${model.specId}")
        appendLine()
        appendLine("> model.jsonld の candidate graph から決定的に生成。人間承認前の候補であり、正しさを保証しません。")
        val candidates = model.nodes
            .filter { it.status == ClaimStatus.CANDIDATE }
            .sortedWith(compareBy(ModelNode::type, ModelNode::id))
        if (candidates.isEmpty()) {
            appendLine()
            appendLine("業務仕様候補はありません。")
            return@buildString
        }

        appendLine()
        appendLine("## 概念")
        appendBusinessSection("用語", candidates, setOf("Term", "Entity", "Type"))
        appendBusinessSection("状態", candidates, setOf("State"))

        appendLine()
        appendLine("## 仕様")
        appendBusinessSection(
            "業務ルール",
            candidates,
            setOf("Requirement", "Constraint", "Invariant", "Precondition", "Postcondition"),
        )
        appendBusinessSection("状態遷移", candidates, setOf("Transition"))
        appendBusinessSection(
            "操作",
            candidates,
            setOf("Operation", "Contract", "Parameter", "Result"),
        )
        appendBusinessSection("例外", candidates, setOf("Error"))
        appendBusinessSection("シナリオ", candidates, setOf("Example", "Counterexample"))
        appendBusinessSection("未確定事項", candidates, setOf("Assumption", "HumanDecision"))

        appendLine()
        appendLine("## 実装根拠")
        appendBusinessSection(
            "コード・テスト・証拠",
            candidates,
            setOf("CodeSymbol", "TestCase", "Evidence"),
        )
    }

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
                node.valueType?.let {
                    appendLine("  - Value type: `${renderValueType(it)}`")
                }
                if (node.members.isNotEmpty()) {
                    appendLine("  - Members: ${node.members.sorted().joinToString { "`$it`" }}")
                }
                node.total?.let {
                    appendLine("  - Total: `$it`")
                }
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
            "ref", "valueRef" -> expression.path("id").asText()
            "enumLiteral" -> "${expression.path("typeId").asText()}.${expression.path("member").asText()}"
            "all" -> {
                val variable = expression.path("variable").asText()
                val domain = renderExpression(expression.path("domain"))
                val body = renderExpression(expression.path("body"))
                "all($variable in $domain, $body)"
            }
            else -> "$op(${expression.path("args").joinToString { renderExpression(it) }})"
        }
    }

    private fun renderValueType(valueType: ValueType): String = when (valueType.kind) {
        ValueKind.INT -> "Int"
        ValueKind.BOOL -> "Bool"
        ValueKind.STRING -> "String"
        ValueKind.ENUM -> "Enum<${valueType.typeId}>"
        ValueKind.SET -> "Set<${renderValueType(valueType.elementType!!)}>"
        ValueKind.LIST -> "List<${renderValueType(valueType.elementType!!)}>"
    }

    private fun StringBuilder.appendBusinessSection(
        title: String,
        candidates: List<ModelNode>,
        types: Set<String>,
    ) {
        appendLine()
        appendLine("### $title")
        val nodes = candidates.filter { it.type in types }
        if (nodes.isEmpty()) {
            appendLine()
            appendLine("候補なし。")
            return
        }
        nodes.forEach { node ->
            appendLine()
            appendLine("- **${node.label}** (`${node.id}`)")
            appendLine(
                "  - 不確実性: `${node.basis.wireValue} / " +
                    "${node.generatedBy ?: "unknown"} / ${node.status.wireValue}`",
            )
            node.valueType?.let {
                appendLine("  - 値型: `${renderValueType(it)}`")
            }
            if (node.members.isNotEmpty()) {
                appendLine("  - 値: ${node.members.sorted().joinToString { "`$it`" }}")
            }
            node.total?.let {
                appendLine("  - 全域: `$it`")
            }
            node.expression?.let {
                appendLine("  - 形式制約: `${renderExpression(it)}`")
            }
            node.relations.toSortedMap().forEach { (relation, targets) ->
                appendLine("  - $relation: ${targets.sorted().joinToString { "`$it`" }}")
            }
            node.evidence
                .sortedWith(compareBy({ it.path }, { it.startLine }, { it.startColumn }))
                .forEach { evidence ->
                    appendLine(
                        "  - 原文根拠: `${evidence.path}:${evidence.startLine}:${evidence.startColumn}` " +
                            "SHA-256 `${evidence.sha256}`",
                    )
                }
        }
    }
}
