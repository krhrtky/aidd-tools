package dev.aidd.model

import com.fasterxml.jackson.databind.JsonNode

object ExpressionValidator {
    private val operations = setOf(
        "literal",
        "ref",
        "variable",
        "current",
        "not",
        "and",
        "or",
        "implies",
        "eq",
        "neq",
        "lt",
        "lte",
        "gt",
        "gte",
        "in",
        "all",
        "some",
        "no",
        "one",
        "always",
        "eventually",
        "next",
        "until",
    )

    fun validate(node: ModelNode, knownNodes: Map<String, ModelNode> = emptyMap()): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val rootSort = infer(node.expression!!, node.id, knownNodes, emptyMap(), diagnostics)
        requireSort(rootSort, Sort.BOOL, "root expression", node.id, diagnostics)
        return diagnostics
    }

    private enum class Sort { BOOL, INT, STRING, SET, INVALID }

    private fun infer(
        expression: JsonNode,
        nodeId: String,
        knownNodes: Map<String, ModelNode>,
        variables: Map<String, Sort>,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        if (!expression.isObject) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "Expression must be an object", nodeId)
            return Sort.INVALID
        }
        val op = expression.path("op").asText()
        if (op !in operations) {
            diagnostics += Diagnostic("UNSUPPORTED_EXPRESSION", "Unsupported expression op: $op", nodeId)
            return Sort.INVALID
        }
        return when (op) {
            "literal" -> {
                if (!expression.has("value")) {
                    diagnostics += Diagnostic("INVALID_EXPRESSION", "literal requires value", nodeId)
                    Sort.INVALID
                } else {
                    when {
                        expression.get("value").isBoolean -> Sort.BOOL
                        expression.get("value").isIntegralNumber -> Sort.INT
                        expression.get("value").isTextual -> Sort.STRING
                        else -> {
                            diagnostics += Diagnostic("INVALID_EXPRESSION_TYPE", "Unsupported literal type", nodeId)
                            Sort.INVALID
                        }
                    }
                }
            }
            "ref" -> {
                if (!expression.path("id").isTextual) {
                    diagnostics += Diagnostic("INVALID_EXPRESSION", "ref requires id", nodeId)
                    Sort.INVALID
                } else if (knownNodes.isNotEmpty() && expression.path("id").asText() !in knownNodes) {
                    diagnostics += Diagnostic(
                        "DANGLING_EXPRESSION_REFERENCE",
                        "Expression refers to missing node: ${expression.path("id").asText()}",
                        nodeId,
                    )
                    Sort.INVALID
                } else if (knownNodes[expression.path("id").asText()]?.type !in
                    setOf("State", "Entity", "Type", "Operation", "Transition")
                ) {
                    diagnostics += Diagnostic(
                        "INVALID_EXPRESSION_REFERENCE_TYPE",
                        "ref must target a formal set-valued node",
                        nodeId,
                    )
                    Sort.INVALID
                } else {
                    Sort.SET
                }
            }
            "variable" -> {
                val name = expression.path("name")
                if (!name.isTextual || name.asText() !in variables) {
                    diagnostics += Diagnostic("UNBOUND_EXPRESSION_VARIABLE", "variable is not bound", nodeId)
                    Sort.INVALID
                } else {
                    variables.getValue(name.asText())
                }
            }
            "current" -> Sort.SET
            "all" -> {
                val variable = expression.path("variable")
                val domain = expression.path("domain")
                val body = expression.path("body")
                if (!variable.isTextual || !domain.isObject || !body.isObject) {
                    diagnostics += Diagnostic(
                        "INVALID_EXPRESSION",
                        "all requires variable, domain, and body",
                        nodeId,
                    )
                    Sort.INVALID
                } else {
                    val domainSort = infer(domain, nodeId, knownNodes, variables, diagnostics)
                    val bodySort = infer(
                        body,
                        nodeId,
                        knownNodes,
                        variables + (variable.asText() to Sort.SET),
                        diagnostics,
                    )
                    requireSort(domainSort, Sort.SET, "all domain", nodeId, diagnostics)
                    requireSort(bodySort, Sort.BOOL, "all body", nodeId, diagnostics)
                    Sort.BOOL
                }
            }
            else -> {
                val args = expression.path("args")
                if (!args.isArray) {
                    diagnostics += Diagnostic("INVALID_EXPRESSION", "$op requires args", nodeId)
                    Sort.INVALID
                } else {
                    val expected = when (op) {
                        "not", "some", "no", "one", "always", "eventually", "next" -> 1..1
                        "implies", "eq", "neq", "lt", "lte", "gt", "gte", "in", "until" -> 2..2
                        "and", "or" -> 2..Int.MAX_VALUE
                        else -> 1..Int.MAX_VALUE
                    }
                    if (args.size() !in expected) {
                        diagnostics += Diagnostic(
                            "INVALID_EXPRESSION_ARITY",
                            "$op expects ${expected.first}..${expected.last} args",
                            nodeId,
                        )
                    }
                    val sorts = args.map { infer(it, nodeId, knownNodes, variables, diagnostics) }
                    val required = when (op) {
                        "not", "and", "or", "implies", "always", "eventually", "next", "until" -> Sort.BOOL
                        "lt", "lte", "gt", "gte" -> Sort.INT
                        "in", "some", "no", "one" -> Sort.SET
                        else -> null
                    }
                    required?.let { expectedSort ->
                        sorts.forEach { requireSort(it, expectedSort, op, nodeId, diagnostics) }
                    }
                    if (op in setOf("eq", "neq") && sorts.size == 2 &&
                        sorts.none { it == Sort.INVALID } && sorts[0] != sorts[1]
                    ) {
                        diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "$op operands must have the same type", nodeId)
                    }
                    when (op) {
                        "some", "no", "one", "not", "and", "or", "implies", "eq", "neq",
                        "lt", "lte", "gt", "gte", "in", "always", "eventually", "next", "until",
                        -> Sort.BOOL
                        else -> Sort.INVALID
                    }
                }
            }
        }
    }

    private fun requireSort(
        actual: Sort,
        expected: Sort,
        operation: String,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (actual != Sort.INVALID && actual != expected) {
            diagnostics += Diagnostic(
                "EXPRESSION_TYPE_MISMATCH",
                "$operation expects $expected but received $actual",
                nodeId,
            )
        }
    }
}
