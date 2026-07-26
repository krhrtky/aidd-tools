package dev.aidd.alloy

import com.fasterxml.jackson.databind.JsonNode
import dev.aidd.model.AiddModel
import dev.aidd.model.ClaimStatus
import dev.aidd.model.Hashing
import dev.aidd.model.ModelNode
import dev.aidd.model.ValueKind
import dev.aidd.model.ValueType

enum class ClaimSelection {
    ACCEPTED_ONLY,
    ACCEPTED_AND_CANDIDATE,
}

class AlloyCompiler {
    fun compile(
        model: AiddModel,
        bounds: Bounds,
        claimSelection: ClaimSelection = ClaimSelection.ACCEPTED_ONLY,
    ): String {
        val selected = model.nodes.filter { node ->
            node.status == ClaimStatus.ACCEPTED ||
                (claimSelection == ClaimSelection.ACCEPTED_AND_CANDIDATE && node.status == ClaimStatus.CANDIDATE)
        }
        val names = allocateNames(selected)
        val lines = mutableListOf<String>()
        lines += "module aidd_${sanitize(model.specId)}"
        lines += ""
        lines += "abstract sig AiddEntity {}"
        lines += "abstract sig AiddType {}"
        lines += "abstract sig AiddState {}"
        lines += "abstract sig AiddOperation {}"
        lines += "abstract sig AiddTransition { from: one AiddState, to: one AiddState }"
        if (selected.any { it.valueType?.contains(ValueKind.BOOL) == true } ||
            selected.any { it.expression?.containsBooleanLiteral() == true }
        ) {
            lines += "abstract sig AiddBool {}"
            lines += "one sig AiddTrue, AiddFalse extends AiddBool {}"
        }
        if (selected.any { it.valueType?.contains(ValueKind.STRING) == true } ||
            selected.any { it.expression?.containsStringLiteral() == true }
        ) {
            lines += "sig AiddString {}"
            selected
                .flatMap { it.expression.stringLiterals() }
                .distinct()
                .sorted()
                .forEach { value ->
                    lines += "one sig ${stringLiteralName(value)} extends AiddString {}"
                }
        }
        selected
            .filter { it.type == "Type" && it.members.isNotEmpty() }
            .sortedBy(ModelNode::id)
            .forEach { type ->
                val enumName = enumTypeName(type, names)
                lines += "abstract sig $enumName {}"
                type.members.sorted().forEach { member ->
                    lines += "one sig ${enumMemberName(type, member, names)} extends $enumName {}"
                }
            }
        lines += ""

        selected.sortedBy(ModelNode::id).forEach { node ->
            val name = names.getValue(node.id)
            when (node.type) {
                "Entity" -> lines += "one sig $name extends AiddEntity {}"
                "Type" -> if (node.members.isEmpty()) lines += "one sig $name extends AiddType {}"
                "State" -> lines += "one sig $name extends AiddState {}"
                "Operation" -> lines += "one sig $name extends AiddOperation {}"
                "Transition" -> {
                    lines += "one sig $name extends AiddTransition {}"
                    val from = node.relations["transitionsFrom"].orEmpty().singleOrNull()?.let(names::get)
                    val to = node.relations["transitionsTo"].orEmpty().singleOrNull()?.let(names::get)
                    if (from != null && to != null) {
                        lines += "fact ${name}_Endpoints {"
                        lines += "  ${name}.from = $from"
                        lines += "  ${name}.to = $to"
                        lines += "}"
                    }
                }
            }
        }
        val transitions = selected.filter { it.type == "Transition" }
        val needsTrace = transitions.isNotEmpty() ||
            selected.any { it.expression?.containsTemporalOperator() == true || it.expression?.containsCurrent() == true }
        if (needsTrace) {
            lines += ""
            lines += "one sig AiddRuntime { var current: one AiddState }"
            val targetStateIds = transitions.flatMap { it.relations["transitionsTo"].orEmpty() }.toSet()
            val initialStates = selected
                .filter { it.type == "State" && it.id !in targetStateIds }
                .mapNotNull { names[it.id] }
                .sorted()
            if (initialStates.isNotEmpty()) {
                lines += "fact InitialState { AiddRuntime.current in ${initialStates.joinToString(" + ")} }"
            }
            val transitionNames = transitions.mapNotNull { names[it.id] }.sorted()
            lines += "fact TransitionTrace {"
            lines += if (transitionNames.isEmpty()) {
                "  always (AiddRuntime.current' = AiddRuntime.current)"
            } else {
                "  always (AiddRuntime.current' = AiddRuntime.current or " +
                    "some t: ${transitionNames.joinToString(" + ")} | " +
                    "(AiddRuntime.current = t.from and AiddRuntime.current' = t.to))"
            }
            lines += "}"
        }

        val contractConstraintIds = selected
            .filter { it.type == "Contract" }
            .flatMap { it.relations["constrains"].orEmpty() }
            .toSet()
        val expressionNodes = selected.filter { it.expression != null && it.id !in contractConstraintIds }
        val constraintNames = allocateConstraintNames(expressionNodes)
        expressionNodes
            .filterNot { it.type == "Invariant" }
            .sortedBy(ModelNode::id)
            .forEach { node ->
                val factName = constraintNames.getValue(node.id)
                lines += ""
                lines += "fact $factName {"
                lines += "  ${compileExpression(node.expression!!, names)}"
                lines += "}"
            }

        expressionNodes
            .filter { it.type == "Invariant" }
            .sortedBy(ModelNode::id)
            .forEach { node ->
                val assertionName = constraintNames.getValue(node.id)
                lines += ""
                lines += "assert $assertionName {"
                lines += "  ${compileExpression(node.expression!!, names)}"
                lines += "}"
            }

        val contractLines = compileContracts(selected, names)
        if (contractLines.isNotEmpty()) {
            lines += ""
            lines += contractLines
        }

        lines += ""
        lines += "pred AiddModel {}"
        val temporalScope = if (needsTrace) {
            ", ${bounds.maxTraceSteps} steps"
        } else {
            ""
        }
        lines += "run AiddModel for ${bounds.globalScope} but ${bounds.intBitwidth} Int$temporalScope"
        expressionNodes
            .filter { it.type == "Invariant" }
            .sortedBy(ModelNode::id)
            .forEach { node ->
                lines += "check ${constraintNames.getValue(node.id)} for ${bounds.globalScope} " +
                    "but ${bounds.intBitwidth} Int$temporalScope"
            }
        selected
            .filter { it.type == "Contract" }
            .sortedBy(ModelNode::id)
            .forEach { contract ->
                val base = contractBaseName(contract)
                val listScope = if (contractUsesList(contract, selected.associateBy(ModelNode::id))) {
                    ", ${bounds.maxListLength} seq"
                } else {
                    ""
                }
                val scope = "for ${bounds.globalScope} but ${bounds.intBitwidth} Int$listScope"
                lines += "run ${base}_PreSatisfiable $scope"
                lines += "check ${base}_ValidResultExactlyOne $scope"
                lines += "check ${base}_ValidHasNoError $scope"
                lines += "check ${base}_ErrorsDisjoint $scope"
                if (contract.total == true) {
                    lines += "check ${base}_TotalInvalidHasExactlyOneError $scope"
                }
            }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun allocateNames(nodes: List<ModelNode>): Map<String, String> {
        val allocated = mutableMapOf<String, String>()
        val used = mutableSetOf<String>()
        nodes.sortedBy(ModelNode::id).forEach { node ->
            val prefix = when (node.type) {
                "State" -> "State"
                "Entity" -> "Entity"
                "Type" -> "Type"
                "Operation" -> "Operation"
                "Transition" -> "Transition"
                else -> node.type
            }
            val base = "${sanitize(prefix)}_${sanitize(node.label)}"
            val name = if (used.add(base)) {
                base
            } else {
                "${base}_${Hashing.sha256(node.id).take(8)}".also(used::add)
            }
            allocated[node.id] = name
        }
        return allocated
    }

    private fun compileContracts(
        selected: List<ModelNode>,
        names: Map<String, String>,
    ): List<String> {
        val nodesById = selected.associateBy(ModelNode::id)
        return buildList {
            selected
                .filter { it.type == "Contract" }
                .sortedBy(ModelNode::id)
                .forEach { contract ->
                    val operation = contract.relations["defines"].orEmpty().single().let(nodesById::getValue)
                    val parameters = operation.relations["accepts"].orEmpty().map(nodesById::getValue)
                    val result = operation.relations["returns"].orEmpty().single().let(nodesById::getValue)
                    val constraints = contract.relations["constrains"].orEmpty().map(nodesById::getValue)
                    val preconditions = constraints.filter { it.type == "Precondition" }
                    val postconditions = constraints.filter { it.type == "Postcondition" }
                    val errors = constraints.filter { it.type == "Error" }.sortedBy(ModelNode::id)
                    val variables = (parameters + result).associate { it.id to valueName(it) }
                    val parameterDeclarations = parameters.joinToString(", ") {
                        "${variables.getValue(it.id)}: ${alloyDeclaration(it.valueType!!, nodesById, names)}"
                    }
                    val resultDeclaration =
                        "${variables.getValue(result.id)}: ${alloyDeclaration(result.valueType!!, nodesById, names)}"
                    val parameterNames = parameters.joinToString(", ") { variables.getValue(it.id) }
                    val preBody = conjunction(
                        preconditions.map { compileExpression(it.expression!!, names, variables) },
                    )
                    val postBody = conjunction(
                        postconditions.map { compileExpression(it.expression!!, names, variables) },
                    )
                    val base = contractBaseName(contract)
                    val preCall = "$base" + "_Pre[$parameterNames]"
                    val postArguments = listOf(parameterNames, variables.getValue(result.id))
                        .filter(String::isNotBlank)
                        .joinToString(", ")
                    val quantifiedParameters = parameterDeclarations.ifBlank { "" }
                    val errorPredicates = errors.map { error ->
                        val predicateName =
                            "${base}_Error_${sanitize(error.label)}_${Hashing.sha256(error.id).take(8)}"
                        add("")
                        add("pred $predicateName[$parameterDeclarations] {")
                        add("  ${compileExpression(error.expression!!, names, variables)}")
                        add("}")
                        predicateName
                    }

                    add("")
                    add("pred ${base}_Pre[$parameterDeclarations] {")
                    add("  $preBody")
                    add("}")
                    add("")
                    add("pred ${base}_Post[${listOf(parameterDeclarations, resultDeclaration).filter(String::isNotBlank).joinToString(", ")}] {")
                    add("  $postBody")
                    add("}")
                    add("")
                    add("pred ${base}_PreSatisfiable {")
                    add(
                        if (quantifiedParameters.isBlank()) {
                            "  $preCall"
                        } else {
                            "  some $quantifiedParameters | $preCall"
                        },
                    )
                    add("}")
                    add("")
                    add("assert ${base}_ValidResultExactlyOne {")
                    val resultObligation = if (result.valueType.kind in setOf(ValueKind.SET, ValueKind.LIST)) {
                        val definition = findCollectionResultDefinition(postconditions, result.id)
                        val compiledDefinition = compileExpression(
                            definition,
                            names,
                            variables,
                            asValue = true,
                        )
                        val substituted = variables + (result.id to compiledDefinition)
                        conjunction(
                            postconditions.map {
                                compileExpression(it.expression!!, names, substituted)
                            },
                        )
                    } else {
                        "one $resultDeclaration | ${base}_Post[$postArguments]"
                    }
                    add(
                        quantifyAll(quantifiedParameters) {
                            "$preCall implies ($resultObligation)"
                        },
                    )
                    add("}")
                    val errorCalls = errorPredicates.map { "$it[$parameterNames]" }
                    add("")
                    add("assert ${base}_ValidHasNoError {")
                    add(
                        quantifyAll(quantifiedParameters) {
                            "$preCall implies not (${disjunction(errorCalls)})"
                        },
                    )
                    add("}")
                    add("")
                    add("assert ${base}_ErrorsDisjoint {")
                    add(
                        quantifyAll(quantifiedParameters) {
                            pairwiseDisjoint(errorCalls)
                        },
                    )
                    add("}")
                    if (contract.total == true) {
                        add("")
                        add("assert ${base}_TotalInvalidHasExactlyOneError {")
                        add(
                            quantifyAll(quantifiedParameters) {
                                "not ($preCall) implies (${exactlyOne(errorCalls)})"
                            },
                        )
                        add("}")
                    }
                }
        }
    }

    private fun compileExpression(
        expression: JsonNode,
        names: Map<String, String>,
        valueNames: Map<String, String> = emptyMap(),
        asValue: Boolean = false,
    ): String {
        val op = expression.path("op").asText()
        if (op == "literal") {
            val value = expression.get("value")
            return when {
                value.isBoolean -> if (asValue) {
                    if (value.asBoolean()) "AiddTrue" else "AiddFalse"
                } else {
                    if (value.asBoolean()) "AiddTrue = AiddTrue" else "AiddTrue = AiddFalse"
                }
                value.isNumber -> value.asText()
                else -> stringLiteralName(value.asText())
            }
        }
        if (op == "ref") {
            return names[expression.path("id").asText()]
                ?: sanitize(expression.path("id").asText().substringAfterLast(':'))
        }
        if (op == "variable") {
            return sanitize(expression.path("name").asText())
        }
        if (op == "valueRef") {
            val value = valueNames[expression.path("id").asText()]
                ?: error("Unbound contract value: ${expression.path("id").asText()}")
            return if (asValue) value else "$value = AiddTrue"
        }
        if (op == "enumLiteral") {
            val typeId = expression.path("typeId").asText()
            val type = names[typeId] ?: sanitize(typeId.substringAfterLast(':'))
            return "${type}_${sanitize(expression.path("member").asText())}"
        }
        if (op == "setLiteral") {
            val values = expression.path("args").map { compileExpression(it, names, valueNames, asValue = true) }
            return if (values.isEmpty()) "none" else values.joinToString(" + ", "(", ")")
        }
        if (op == "listLiteral") {
            val values = expression.path("args").mapIndexed { index, value ->
                "$index -> ${compileExpression(value, names, valueNames, asValue = true)}"
            }
            return if (values.isEmpty()) "(none -> none)" else values.joinToString(" + ", "(", ")")
        }
        if (op == "current") {
            return "AiddRuntime.current"
        }
        fun args(values: Boolean = false): List<String> =
            expression.path("args").map { compileExpression(it, names, valueNames, values) }
        return when (op) {
            "not" -> "not (${args().single()})"
            "and" -> args().joinToString(" and ", "(", ")")
            "or" -> args().joinToString(" or ", "(", ")")
            "implies" -> "(${args().requireArity(2)[0]}) implies (${args()[1]})"
            "eq" -> "(${args(true).requireArity(2)[0]}) = (${args(true)[1]})"
            "neq" -> "(${args(true).requireArity(2)[0]}) != (${args(true)[1]})"
            "lt" -> "(${args(true).requireArity(2)[0]}) < (${args(true)[1]})"
            "lte" -> "(${args(true).requireArity(2)[0]}) =< (${args(true)[1]})"
            "gt" -> "(${args(true).requireArity(2)[0]}) > (${args(true)[1]})"
            "gte" -> "(${args(true).requireArity(2)[0]}) >= (${args(true)[1]})"
            "in" -> "(${args(true).requireArity(2)[0]}) in (${args(true)[1]})"
            "contains" -> "(${args(true).requireArity(2)[1]}) in (${args(true)[0]})"
            "add" -> args(true).reduce { left, right -> "($left).add[$right]" }
            "sub" -> "(${args(true).requireArity(2)[0]}).sub[${args(true)[1]}]"
            "mul" -> args(true).reduce { left, right -> "($left).mul[$right]" }
            "size" -> "#(${args(true).single()})"
            "index" -> "(${args(true).requireArity(2)[0]})[${args(true)[1]}]"
            "union" -> "(${args(true).requireArity(2)[0]}) + (${args(true)[1]})"
            "intersect" -> "(${args(true).requireArity(2)[0]}) & (${args(true)[1]})"
            "difference" -> "(${args(true).requireArity(2)[0]}) - (${args(true)[1]})"
            "append" -> "(${args(true).requireArity(2)[0]}).add[${args(true)[1]}]"
            "concat" -> "(${args(true).requireArity(2)[0]}).append[${args(true)[1]}]"
            "slice" -> "(${args(true).requireArity(3)[0]}).subseq[${args(true)[1]}, ${args(true)[2]}]"
            "all" -> {
                val variable = sanitize(expression.path("variable").asText())
                val domain = compileExpression(expression.path("domain"), names, valueNames, asValue = true)
                val body = compileExpression(expression.path("body"), names, valueNames)
                "all $variable: $domain | ($body)"
            }
            "some" -> "some (${args(true).single()})"
            "no" -> "no (${args(true).single()})"
            "one" -> "one (${args(true).single()})"
            "always" -> "always (${args().single()})"
            "eventually" -> "eventually (${args().single()})"
            "next" -> "after (${args().single()})"
            "until" -> "(${args().requireArity(2)[0]}) until (${args()[1]})"
            else -> error("Unsupported expression operation: $op")
        }
    }

    private fun sanitize(value: String): String {
        val sanitized = value
            .replace(Regex("[^A-Za-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        val nonEmpty = sanitized.ifBlank { "Unnamed" }
        return if (nonEmpty.first().isDigit()) "_$nonEmpty" else nonEmpty
    }

    private fun allocateConstraintNames(nodes: List<ModelNode>): Map<String, String> {
        val allocated = mutableMapOf<String, String>()
        val used = mutableSetOf<String>()
        nodes.sortedBy(ModelNode::id).forEach { node ->
            val base = "Constraint_${sanitize(node.label)}"
            val name = if (used.add(base)) {
                base
            } else {
                "${base}_${Hashing.sha256(node.id).take(8)}".also(used::add)
            }
            allocated[node.id] = name
        }
        return allocated
    }

    private fun contractBaseName(contract: ModelNode): String =
        "Contract_${sanitize(contract.label)}_${Hashing.sha256(contract.id).take(8)}"

    private fun valueName(node: ModelNode): String =
        "${sanitize(node.label).replaceFirstChar(Char::lowercase)}_${Hashing.sha256(node.id).take(8)}"

    private fun enumTypeName(type: ModelNode, names: Map<String, String>): String =
        names.getValue(type.id)

    private fun enumMemberName(type: ModelNode, member: String, names: Map<String, String>): String =
        "${enumTypeName(type, names)}_${sanitize(member)}"

    private fun stringLiteralName(value: String): String =
        "StringLiteral_${Hashing.sha256(value).take(12)}"

    private fun alloyDeclaration(
        type: ValueType,
        nodesById: Map<String, ModelNode>,
        names: Map<String, String>,
    ): String = when (type.kind) {
        ValueKind.INT -> "Int"
        ValueKind.BOOL -> "AiddBool"
        ValueKind.STRING -> "AiddString"
        ValueKind.ENUM -> enumTypeName(nodesById.getValue(type.typeId!!), names)
        ValueKind.SET -> "set ${alloyDeclaration(type.elementType!!, nodesById, names)}"
        ValueKind.LIST -> "seq ${alloyDeclaration(type.elementType!!, nodesById, names)}"
    }

    private fun conjunction(expressions: List<String>): String =
        expressions.ifEmpty { listOf("no none") }.joinToString(" and ", "(", ")")

    private fun disjunction(expressions: List<String>): String =
        expressions.ifEmpty { listOf("some none") }.joinToString(" or ", "(", ")")

    private fun pairwiseDisjoint(expressions: List<String>): String {
        val pairs = expressions.flatMapIndexed { index, left ->
            expressions.drop(index + 1).map { right -> "not (($left) and ($right))" }
        }
        return conjunction(pairs)
    }

    private fun exactlyOne(expressions: List<String>): String =
        "${disjunction(expressions)} and ${pairwiseDisjoint(expressions)}"

    private fun quantifyAll(declarations: String, body: () -> String): String =
        if (declarations.isBlank()) {
            "  ${body()}"
        } else {
            "  all $declarations | (${body()})"
        }

    private fun contractUsesList(contract: ModelNode, nodesById: Map<String, ModelNode>): Boolean {
        val operation = contract.relations["defines"].orEmpty().single().let(nodesById::getValue)
        return (
            operation.relations["accepts"].orEmpty() +
                operation.relations["returns"].orEmpty()
            ).map(nodesById::getValue).any { it.valueType?.contains(ValueKind.LIST) == true }
    }

    private fun findCollectionResultDefinition(
        postconditions: List<ModelNode>,
        resultId: String,
    ): JsonNode {
        val definitions = postconditions.flatMap { postcondition ->
            postcondition.expression!!.collectionResultDefinitions(resultId)
        }
        require(definitions.size == 1) {
            "Collection Result requires exactly one conjunctive defining equality in Postcondition"
        }
        return definitions.single()
    }
}

private fun JsonNode.containsCurrent(): Boolean {
    if (path("op").asText() == "current") return true
    return path("args").takeIf(JsonNode::isArray)?.any(JsonNode::containsCurrent) == true ||
        path("domain").takeIf(JsonNode::isObject)?.containsCurrent() == true ||
        path("body").takeIf(JsonNode::isObject)?.containsCurrent() == true
}

private fun List<String>.requireArity(expected: Int): List<String> {
    require(size == expected) { "Expected $expected expression arguments, got $size" }
    return this
}

private fun JsonNode.containsTemporalOperator(): Boolean {
    if (path("op").asText() in setOf("always", "eventually", "next", "until")) {
        return true
    }
    return path("args").takeIf(JsonNode::isArray)?.any(JsonNode::containsTemporalOperator) == true ||
        path("domain").takeIf(JsonNode::isObject)?.containsTemporalOperator() == true ||
        path("body").takeIf(JsonNode::isObject)?.containsTemporalOperator() == true
}

private fun ValueType.contains(target: ValueKind): Boolean =
    kind == target || elementType?.contains(target) == true

private fun JsonNode?.containsBooleanLiteral(): Boolean {
    if (this == null) return false
    if (path("op").asText() == "literal" && path("value").isBoolean) return true
    return path("args").takeIf(JsonNode::isArray)?.any { it.containsBooleanLiteral() } == true ||
        path("domain").takeIf(JsonNode::isObject)?.containsBooleanLiteral() == true ||
        path("body").takeIf(JsonNode::isObject)?.containsBooleanLiteral() == true
}

private fun JsonNode?.containsStringLiteral(): Boolean = stringLiterals().isNotEmpty()

private fun JsonNode?.stringLiterals(): List<String> {
    if (this == null) return emptyList()
    val own = if (path("op").asText() == "literal" && path("value").isTextual) {
        listOf(path("value").asText())
    } else {
        emptyList()
    }
    return own +
        path("args").takeIf(JsonNode::isArray)?.flatMap { it.stringLiterals() }.orEmpty() +
        path("domain").takeIf(JsonNode::isObject).stringLiterals() +
        path("body").takeIf(JsonNode::isObject).stringLiterals()
}

private fun JsonNode.isValueReference(id: String): Boolean =
    path("op").asText() == "valueRef" && path("id").asText() == id

private fun JsonNode.collectionResultDefinitions(resultId: String): List<JsonNode> {
    if (path("op").asText() == "and") {
        return path("args").flatMap { it.collectionResultDefinitions(resultId) }
    }
    if (path("op").asText() != "eq") return emptyList()
    val args = path("args")
    if (!args.isArray || args.size() != 2) return emptyList()
    return when {
        args[0].isValueReference(resultId) -> listOf(args[1])
        args[1].isValueReference(resultId) -> listOf(args[0])
        else -> emptyList()
    }
}
