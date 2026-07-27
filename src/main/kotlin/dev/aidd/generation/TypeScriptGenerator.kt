package dev.aidd.generation

import com.fasterxml.jackson.databind.JsonNode
import dev.aidd.model.AiddModel
import dev.aidd.model.ClaimStatus
import dev.aidd.model.ModelNode
import dev.aidd.model.ValueKind
import dev.aidd.model.ValueType

class UnsupportedGenerationException(message: String) : RuntimeException(message)

class TypeScriptGenerator {
    fun generate(model: AiddModel, contractId: String): String {
        val nodes = model.nodes.associateBy(ModelNode::id)
        val contract = nodes[contractId]
            ?: throw UnsupportedGenerationException("Contract does not exist: $contractId")
        requireAccepted(contract)
        if (contract.type != "Contract" || contract.total != true) {
            throw UnsupportedGenerationException("Generator supports only total accepted contracts")
        }
        val operation = contract.relations["defines"].orEmpty().singleOrNull()?.let(nodes::get)
            ?: throw UnsupportedGenerationException("Contract must define exactly one operation")
        requireAccepted(operation)
        val parameters = operation.relations["accepts"].orEmpty().map(nodes::getValue)
        val result = operation.relations["returns"].orEmpty().singleOrNull()?.let(nodes::get)
            ?: throw UnsupportedGenerationException("Operation must return exactly one result")
        (parameters + result).forEach(::requireAccepted)
        val constraints = contract.relations["constrains"].orEmpty().map(nodes::getValue)
        constraints.forEach(::requireAccepted)
        val postconditions = constraints.filter { it.type == "Postcondition" }
        val errors = constraints.filter { it.type == "Error" }
        if (errors.isEmpty()) {
            throw UnsupportedGenerationException("Result generator requires explicit Error outcomes")
        }
        val resultExpression = deterministicResultExpression(postconditions, result.id)
        val parameterNames = parameters.associate { it.id to it.label }
        val resultType = compileType(result.valueType!!, nodes)
        val resultName = "${operation.label.replaceFirstChar(Char::uppercase)}Result"
        val errorUnion = errors.map(ModelNode::label).sorted().joinToString(" | ", transform = ::quote)
        val contractTrace = contract.id

        return buildString {
            appendLine("// Generated deterministically from accepted canonical contract $contractTrace.")
            appendLine("export type $resultName =")
            appendLine("  | { ok: true; value: $resultType }")
            appendLine("  | { ok: false; error: $errorUnion };")
            appendLine()
            appendLine("/** @aidd.contract $contractTrace */")
            append("export function ${operation.label}(")
            append(parameters.joinToString(", ") { "${it.label}: ${compileType(it.valueType!!, nodes)}" })
            appendLine("): $resultName {")
            errors.forEach { error ->
                appendLine(
                    "  if (${compileExpression(error.expression!!, parameterNames)}) " +
                        "return { ok: false, error: ${quote(error.label)} };",
                )
            }
            appendLine(
                "  return { ok: true, value: " +
                    "${compileExpression(resultExpression, parameterNames)} };",
            )
            appendLine("}")
        }
    }

    private fun deterministicResultExpression(postconditions: List<ModelNode>, resultId: String): JsonNode {
        val definitions = postconditions.flatMap { findDefinitions(it.expression!!, resultId) }
        if (definitions.size != 1) {
            throw UnsupportedGenerationException(
                "Result requires exactly one deterministic postcondition equality",
            )
        }
        return definitions.single()
    }

    private fun findDefinitions(expression: JsonNode, resultId: String): List<JsonNode> {
        if (expression.path("op").asText() == "and") {
            return expression.path("args").flatMap { findDefinitions(it, resultId) }
        }
        if (expression.path("op").asText() != "eq" || expression.path("args").size() != 2) {
            return emptyList()
        }
        val left = expression.path("args")[0]
        val right = expression.path("args")[1]
        return when {
            isValueRef(left, resultId) -> listOf(right)
            isValueRef(right, resultId) -> listOf(left)
            else -> emptyList()
        }
    }

    private fun compileExpression(expression: JsonNode, names: Map<String, String>): String {
        return when (val op = expression.path("op").asText()) {
            "literal" -> when {
                expression.path("value").isIntegralNumber -> "${expression.path("value").asText()}n"
                expression.path("value").isBoolean -> expression.path("value").asText()
                expression.path("value").isTextual -> quote(expression.path("value").asText())
                else -> unsupportedExpression(op)
            }
            "valueRef" -> names[expression.path("id").asText()]
                ?: throw UnsupportedGenerationException(
                    "Expression references a value outside the operation: ${expression.path("id").asText()}",
                )
            else -> {
                val args = expression.path("args").map { compileExpression(it, names) }
                when (op) {
                    "not" -> "!(${args.single()})"
                    "and" -> args.joinToString(" && ", "(", ")")
                    "or" -> args.joinToString(" || ", "(", ")")
                    "eq" -> binary(args, "===")
                    "neq" -> binary(args, "!==")
                    "lt" -> binary(args, "<")
                    "lte" -> binary(args, "<=")
                    "gt" -> binary(args, ">")
                    "gte" -> binary(args, ">=")
                    "add" -> binary(args, "+")
                    "sub" -> binary(args, "-")
                    "mul" -> binary(args, "*")
                    else -> unsupportedExpression(op)
                }
            }
        }
    }

    private fun compileType(type: ValueType, nodes: Map<String, ModelNode>): String = when (type.kind) {
        ValueKind.INT -> "bigint"
        ValueKind.BOOL -> "boolean"
        ValueKind.STRING -> "string"
        ValueKind.ENUM -> nodes[type.typeId]?.members?.sorted()?.joinToString(" | ") { quote(it) }
            ?: throw UnsupportedGenerationException("Enum type does not exist: ${type.typeId}")
        ValueKind.SET -> "ReadonlySet<${compileType(type.elementType!!, nodes)}>"
        ValueKind.LIST -> "ReadonlyArray<${compileType(type.elementType!!, nodes)}>"
    }

    private fun requireAccepted(node: ModelNode) {
        if (node.status != ClaimStatus.ACCEPTED) {
            throw UnsupportedGenerationException("Generator input is not accepted: ${node.id}")
        }
    }

    private fun isValueRef(expression: JsonNode, id: String): Boolean =
        expression.path("op").asText() == "valueRef" && expression.path("id").asText() == id

    private fun binary(args: List<String>, operator: String): String {
        if (args.size != 2) throw UnsupportedGenerationException("$operator requires two operands")
        return "${args[0]} $operator ${args[1]}"
    }

    private fun unsupportedExpression(op: String): Nothing =
        throw UnsupportedGenerationException("Unsupported generator expression: $op")

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
