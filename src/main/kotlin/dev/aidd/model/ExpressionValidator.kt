package dev.aidd.model

import com.fasterxml.jackson.databind.JsonNode

object ExpressionValidator {
    private val operations = setOf(
        "literal",
        "ref",
        "valueRef",
        "enumLiteral",
        "setLiteral",
        "listLiteral",
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

    fun validate(node: ModelNode, knownNodes: Map<String, ModelNode> = emptyMap()): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val rootSort = infer(node.expression!!, node.id, knownNodes, emptyMap(), diagnostics)
        requireSort(rootSort, Sort.Bool, "root expression", node.id, diagnostics)
        return diagnostics
    }

    private sealed interface Sort {
        data object Bool : Sort
        data object Int : Sort
        data object StringValue : Sort
        data class EnumValue(val typeId: String) : Sort
        data class SetValue(val element: Sort) : Sort
        data class ListValue(val element: Sort) : Sort
        data object Unknown : Sort
        data object Invalid : Sort
    }

    private fun infer(
        expression: JsonNode,
        nodeId: String,
        knownNodes: Map<String, ModelNode>,
        variables: Map<String, Sort>,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        if (!expression.isObject) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "Expression must be an object", nodeId)
            return Sort.Invalid
        }
        val op = expression.path("op").asText()
        if (op !in operations) {
            diagnostics += Diagnostic("UNSUPPORTED_EXPRESSION", "Unsupported expression op: $op", nodeId)
            return Sort.Invalid
        }
        return when (op) {
            "literal" -> inferLiteral(expression, nodeId, diagnostics)
            "ref" -> inferFormalReference(expression, nodeId, knownNodes, diagnostics)
            "valueRef" -> inferValueReference(expression, nodeId, knownNodes, diagnostics)
            "enumLiteral" -> inferEnumLiteral(expression, nodeId, knownNodes, diagnostics)
            "setLiteral", "listLiteral" -> inferCollectionLiteral(
                expression,
                op,
                nodeId,
                knownNodes,
                variables,
                diagnostics,
            )
            "variable" -> {
                val name = expression.path("name")
                if (!name.isTextual || name.asText() !in variables) {
                    diagnostics += Diagnostic("UNBOUND_EXPRESSION_VARIABLE", "variable is not bound", nodeId)
                    Sort.Invalid
                } else {
                    variables.getValue(name.asText())
                }
            }
            "current" -> Sort.SetValue(Sort.Unknown)
            "all" -> inferQuantifier(expression, nodeId, knownNodes, variables, diagnostics)
            else -> inferOperation(expression, op, nodeId, knownNodes, variables, diagnostics)
        }
    }

    private fun inferLiteral(
        expression: JsonNode,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        if (!expression.has("value")) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "literal requires value", nodeId)
            return Sort.Invalid
        }
        return when {
            expression.get("value").isBoolean -> Sort.Bool
            expression.get("value").isIntegralNumber -> Sort.Int
            expression.get("value").isTextual -> Sort.StringValue
            else -> {
                diagnostics += Diagnostic("INVALID_EXPRESSION_TYPE", "Unsupported literal type", nodeId)
                Sort.Invalid
            }
        }
    }

    private fun inferFormalReference(
        expression: JsonNode,
        nodeId: String,
        knownNodes: Map<String, ModelNode>,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val id = expression.path("id")
        if (!id.isTextual) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "ref requires id", nodeId)
            return Sort.Invalid
        }
        val target = knownNodes[id.asText()]
        if (knownNodes.isNotEmpty() && target == null) {
            diagnostics += Diagnostic(
                "DANGLING_EXPRESSION_REFERENCE",
                "Expression refers to missing node: ${id.asText()}",
                nodeId,
            )
            return Sort.Invalid
        }
        if (target?.type !in setOf("State", "Entity", "Type", "Operation", "Transition")) {
            diagnostics += Diagnostic(
                "INVALID_EXPRESSION_REFERENCE_TYPE",
                "ref must target a formal set-valued node",
                nodeId,
            )
            return Sort.Invalid
        }
        return Sort.SetValue(Sort.Unknown)
    }

    private fun inferValueReference(
        expression: JsonNode,
        nodeId: String,
        knownNodes: Map<String, ModelNode>,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val id = expression.path("id")
        if (!id.isTextual) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "valueRef requires id", nodeId)
            return Sort.Invalid
        }
        val target = knownNodes[id.asText()]
        if (target == null) {
            diagnostics += Diagnostic(
                "DANGLING_EXPRESSION_REFERENCE",
                "Expression refers to missing value node: ${id.asText()}",
                nodeId,
            )
            return Sort.Invalid
        }
        if (target.type !in setOf("Parameter", "Result")) {
            diagnostics += Diagnostic(
                "INVALID_EXPRESSION_REFERENCE_TYPE",
                "valueRef must target a Parameter or Result",
                nodeId,
            )
            return Sort.Invalid
        }
        return target.valueType?.toSort() ?: Sort.Invalid
    }

    private fun inferEnumLiteral(
        expression: JsonNode,
        nodeId: String,
        knownNodes: Map<String, ModelNode>,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val typeId = expression.path("typeId")
        val member = expression.path("member")
        if (!typeId.isTextual || !member.isTextual) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "enumLiteral requires typeId and member", nodeId)
            return Sort.Invalid
        }
        val type = knownNodes[typeId.asText()]
        if (type?.type != "Type" || type.members.isEmpty()) {
            diagnostics += Diagnostic(
                "INVALID_ENUM_TYPE",
                "enumLiteral typeId must target an enum Type",
                nodeId,
            )
            return Sort.Invalid
        }
        if (member.asText() !in type.members) {
            diagnostics += Diagnostic(
                "UNKNOWN_ENUM_MEMBER",
                "${member.asText()} is not a member of ${typeId.asText()}",
                nodeId,
            )
        }
        return Sort.EnumValue(typeId.asText())
    }

    private fun inferCollectionLiteral(
        expression: JsonNode,
        op: String,
        nodeId: String,
        knownNodes: Map<String, ModelNode>,
        variables: Map<String, Sort>,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val declaredType = expression.get("valueType")?.let {
            runCatching { parseExpressionValueType(it) }.getOrElse { exception ->
                diagnostics += Diagnostic("INVALID_VALUE_TYPE", exception.message ?: "Invalid valueType", nodeId)
                null
            }
        }
        if (declaredType == null) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "$op requires valueType", nodeId)
            return Sort.Invalid
        }
        if (declaredType.kind in setOf(ValueKind.SET, ValueKind.LIST)) {
            diagnostics += Diagnostic("NESTED_COLLECTION_UNSUPPORTED", "Collection nesting is unsupported", nodeId)
            return Sort.Invalid
        }
        if (declaredType.kind == ValueKind.ENUM) {
            val enumType = declaredType.typeId?.let(knownNodes::get)
            if (enumType?.type != "Type" || enumType.members.isEmpty()) {
                diagnostics += Diagnostic(
                    "INVALID_ENUM_TYPE",
                    "$op enum valueType must target a Type with non-empty members",
                    nodeId,
                )
                return Sort.Invalid
            }
        }
        val elementSort = declaredType.toSort()
        val args = expression.path("args")
        if (!args.isArray) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "$op requires args", nodeId)
            return Sort.Invalid
        }
        args.map { infer(it, nodeId, knownNodes, variables, diagnostics) }
            .forEach { requireCompatible(it, elementSort, "$op element", nodeId, diagnostics) }
        return if (op == "setLiteral") Sort.SetValue(elementSort) else Sort.ListValue(elementSort)
    }

    private fun inferQuantifier(
        expression: JsonNode,
        nodeId: String,
        knownNodes: Map<String, ModelNode>,
        variables: Map<String, Sort>,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val variable = expression.path("variable")
        val domain = expression.path("domain")
        val body = expression.path("body")
        if (!variable.isTextual || !domain.isObject || !body.isObject) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "all requires variable, domain, and body", nodeId)
            return Sort.Invalid
        }
        val domainSort = infer(domain, nodeId, knownNodes, variables, diagnostics)
        val elementSort = (domainSort as? Sort.SetValue)?.element ?: Sort.Invalid
        if (domainSort !is Sort.SetValue && domainSort != Sort.Invalid) {
            diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "all domain expects a set", nodeId)
        }
        val bodySort = infer(
            body,
            nodeId,
            knownNodes,
            variables + (variable.asText() to elementSort),
            diagnostics,
        )
        requireSort(bodySort, Sort.Bool, "all body", nodeId, diagnostics)
        return Sort.Bool
    }

    private fun inferOperation(
        expression: JsonNode,
        op: String,
        nodeId: String,
        knownNodes: Map<String, ModelNode>,
        variables: Map<String, Sort>,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val args = expression.path("args")
        if (!args.isArray) {
            diagnostics += Diagnostic("INVALID_EXPRESSION", "$op requires args", nodeId)
            return Sort.Invalid
        }
        val expected = when (op) {
            "not", "some", "no", "one", "always", "eventually", "next", "size" -> 1..1
            "implies", "eq", "neq", "lt", "lte", "gt", "gte", "in", "until",
            "contains", "index", "union", "intersect", "difference", "append", "concat",
            -> 2..2
            "slice" -> 3..3
            "and", "or", "add", "mul" -> 2..Int.MAX_VALUE
            "sub" -> 2..2
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
        return when (op) {
            "not", "and", "or", "implies", "always", "eventually", "next", "until" -> {
                sorts.forEach { requireSort(it, Sort.Bool, op, nodeId, diagnostics) }
                Sort.Bool
            }
            "eq", "neq" -> {
                if (sorts.size == 2) requireCompatible(sorts[0], sorts[1], op, nodeId, diagnostics)
                Sort.Bool
            }
            "lt", "lte", "gt", "gte" -> {
                sorts.forEach { requireSort(it, Sort.Int, op, nodeId, diagnostics) }
                Sort.Bool
            }
            "add", "sub", "mul" -> {
                sorts.forEach { requireSort(it, Sort.Int, op, nodeId, diagnostics) }
                Sort.Int
            }
            "in", "contains" -> inferContains(op, sorts, nodeId, diagnostics)
            "some", "no", "one" -> {
                sorts.forEach {
                    if (it !is Sort.SetValue && it != Sort.Invalid) {
                        diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "$op expects a set", nodeId)
                    }
                }
                Sort.Bool
            }
            "size" -> {
                val collection = sorts.firstOrNull()
                if (collection !is Sort.SetValue && collection !is Sort.ListValue && collection != Sort.Invalid) {
                    diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "size expects a collection", nodeId)
                }
                Sort.Int
            }
            "index" -> {
                val list = sorts.getOrNull(0)
                requireSort(sorts.getOrNull(1) ?: Sort.Invalid, Sort.Int, "index", nodeId, diagnostics)
                if (list !is Sort.ListValue && list != Sort.Invalid) {
                    diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "index expects a list", nodeId)
                }
                validateStaticIndexBounds(args, nodeId, diagnostics)
                (list as? Sort.ListValue)?.element ?: Sort.Invalid
            }
            "union", "intersect", "difference" -> inferSetOperation(op, sorts, nodeId, diagnostics)
            "append" -> inferAppend(sorts, nodeId, diagnostics)
            "concat" -> inferConcat(sorts, nodeId, diagnostics)
            "slice" -> inferSlice(sorts, nodeId, diagnostics)
            else -> Sort.Invalid
        }
    }

    private fun inferContains(
        op: String,
        sorts: List<Sort>,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        if (sorts.size != 2) return Sort.Bool
        val collectionIndex = if (op == "contains") 0 else 1
        val valueIndex = 1 - collectionIndex
        val collection = sorts[collectionIndex]
        val element = when (collection) {
            is Sort.SetValue -> collection.element
            is Sort.ListValue -> collection.element
            Sort.Invalid -> Sort.Invalid
            else -> {
                diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "$op expects a collection", nodeId)
                Sort.Invalid
            }
        }
        requireCompatible(sorts[valueIndex], element, op, nodeId, diagnostics)
        return Sort.Bool
    }

    private fun inferSetOperation(
        op: String,
        sorts: List<Sort>,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val left = sorts.getOrNull(0)
        val right = sorts.getOrNull(1)
        if (left !is Sort.SetValue || right !is Sort.SetValue) {
            if (left != Sort.Invalid && right != Sort.Invalid) {
                diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "$op expects two sets", nodeId)
            }
            return Sort.Invalid
        }
        requireCompatible(left.element, right.element, op, nodeId, diagnostics)
        return left
    }

    private fun validateStaticIndexBounds(
        args: JsonNode,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (args.size() != 2) return
        val list = args[0]
        val index = args[1]
        if (
            list.path("op").asText() != "listLiteral" ||
            index.path("op").asText() != "literal" ||
            !index.path("value").isIntegralNumber
        ) {
            return
        }
        val value = index.path("value").asInt()
        val length = list.path("args").takeIf(JsonNode::isArray)?.size() ?: return
        if (value !in 0 until length) {
            diagnostics += Diagnostic(
                "INDEX_OUT_OF_RANGE",
                "index $value is outside the literal list range 0..${length - 1}",
                nodeId,
            )
        }
    }

    private fun inferAppend(
        sorts: List<Sort>,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val list = sorts.getOrNull(0)
        if (list !is Sort.ListValue) {
            if (list != Sort.Invalid) diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "append expects a list", nodeId)
            return Sort.Invalid
        }
        requireCompatible(sorts.getOrNull(1) ?: Sort.Invalid, list.element, "append", nodeId, diagnostics)
        return list
    }

    private fun inferConcat(
        sorts: List<Sort>,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val left = sorts.getOrNull(0)
        val right = sorts.getOrNull(1)
        if (left !is Sort.ListValue || right !is Sort.ListValue) {
            if (left != Sort.Invalid && right != Sort.Invalid) {
                diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "concat expects two lists", nodeId)
            }
            return Sort.Invalid
        }
        requireCompatible(left.element, right.element, "concat", nodeId, diagnostics)
        return left
    }

    private fun inferSlice(
        sorts: List<Sort>,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Sort {
        val list = sorts.getOrNull(0)
        if (list !is Sort.ListValue) {
            if (list != Sort.Invalid) diagnostics += Diagnostic("EXPRESSION_TYPE_MISMATCH", "slice expects a list", nodeId)
            return Sort.Invalid
        }
        sorts.drop(1).forEach { requireSort(it, Sort.Int, "slice", nodeId, diagnostics) }
        return list
    }

    private fun ValueType.toSort(): Sort = when (kind) {
        ValueKind.INT -> Sort.Int
        ValueKind.BOOL -> Sort.Bool
        ValueKind.STRING -> Sort.StringValue
        ValueKind.ENUM -> typeId?.let(Sort::EnumValue) ?: Sort.Invalid
        ValueKind.SET -> elementType?.toSort()?.let(Sort::SetValue) ?: Sort.Invalid
        ValueKind.LIST -> elementType?.toSort()?.let(Sort::ListValue) ?: Sort.Invalid
    }

    private fun parseExpressionValueType(node: JsonNode): ValueType {
        require(node.isObject) { "valueType must be an object" }
        val allowed = setOf("kind", "typeId", "elementType")
        require(node.fieldNames().asSequence().all(allowed::contains)) { "Unknown valueType field" }
        val kindNode = node.get("kind")
        require(kindNode?.isTextual == true) { "valueType kind is required" }
        val kind = ValueKind.fromWire(kindNode.asText())
        return ValueType(
            kind = kind,
            typeId = node.path("typeId").takeIf(JsonNode::isTextual)?.asText(),
            elementType = node.get("elementType")?.let(::parseExpressionValueType),
        )
    }

    private fun requireSort(
        actual: Sort,
        expected: Sort,
        operation: String,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (actual != Sort.Invalid && actual != expected) {
            diagnostics += Diagnostic(
                "EXPRESSION_TYPE_MISMATCH",
                "$operation expects ${expected.display()} but received ${actual.display()}",
                nodeId,
            )
        }
    }

    private fun requireCompatible(
        actual: Sort,
        expected: Sort,
        operation: String,
        nodeId: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (actual == Sort.Invalid || expected == Sort.Invalid || actual == Sort.Unknown || expected == Sort.Unknown) return
        if (actual != expected) {
            diagnostics += Diagnostic(
                "EXPRESSION_TYPE_MISMATCH",
                "$operation operands must have the same type (${actual.display()} != ${expected.display()})",
                nodeId,
            )
        }
    }

    private fun Sort.display(): String = when (this) {
        Sort.Bool -> "BOOL"
        Sort.Int -> "INT"
        Sort.StringValue -> "STRING"
        is Sort.EnumValue -> "ENUM($typeId)"
        is Sort.SetValue -> "SET<${element.display()}>"
        is Sort.ListValue -> "LIST<${element.display()}>"
        Sort.Unknown -> "UNKNOWN"
        Sort.Invalid -> "INVALID"
    }
}
