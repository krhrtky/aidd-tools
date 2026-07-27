package dev.aidd.refinement

import com.fasterxml.jackson.databind.JsonNode
import dev.aidd.alloy.AlloyCompiler
import dev.aidd.alloy.Bounds
import dev.aidd.model.AiddModel
import dev.aidd.model.Hashing
import dev.aidd.model.ModelNode
import dev.aidd.model.ValueKind
import dev.aidd.model.ValueType

class RefinementCompiler(
    private val specificationCompiler: AlloyCompiler = AlloyCompiler(),
) {
    fun compile(
        model: AiddModel,
        observed: ObservedContract,
        contractId: String,
        bounds: Bounds,
    ): String {
        val nodesById = model.nodes.associateBy(ModelNode::id)
        val contract = nodesById[contractId]
            ?: throw UnsupportedRefinementException("Accepted contract does not exist: $contractId")
        if (contract.type != "Contract" || contract.status.wireValue != "accepted") {
            throw UnsupportedRefinementException("Refinement requires an accepted Contract: $contractId")
        }
        val operation = contract.relations["defines"].orEmpty().singleOrNull()?.let(nodesById::get)
            ?: throw UnsupportedRefinementException("Contract must define exactly one operation")
        val parameters = operation.relations["accepts"].orEmpty().map(nodesById::getValue)
        val result = operation.relations["returns"].orEmpty().singleOrNull()?.let(nodesById::get)
            ?: throw UnsupportedRefinementException("Operation must return exactly one result")
        val constraints = contract.relations["constrains"].orEmpty().map(nodesById::getValue)
        val errors = constraints.filter { it.type == "Error" }.sortedBy(ModelNode::id)

        validateTypes(parameters, result, observed, nodesById)
        validateErrors(errors, observed)

        val variables = parameters.associate { it.label to valueName(it) }
        val parameterDeclarations = parameters.joinToString(", ") {
            "${valueName(it)}: ${alloyDeclaration(it.valueType!!, nodesById)}"
        }
        val parameterNames = parameters.joinToString(", ") { valueName(it) }
        val resultName = valueName(result)
        val resultDeclaration = "$resultName: ${alloyDeclaration(result.valueType!!, nodesById)}"
        val resultArguments = listOf(parameterNames, resultName).filter(String::isNotBlank).joinToString(", ")
        val preCall = "${contractBaseName(contract)}_Pre[$parameterNames]"
        val postCall = "${contractBaseName(contract)}_Post[$resultArguments]"

        val rawConditions = observed.cases.map { compileExpression(it.condition, variables) }
        val effectiveConditions = rawConditions.mapIndexed { index, condition ->
            val previous = rawConditions.take(index)
            if (previous.isEmpty()) "($condition)" else {
                "($condition) and not (${previous.joinToString(" or ", "(", ")")})"
            }
        }
        val successBranches = observed.cases.mapIndexedNotNull { index, case ->
            if (case.outcome.path("kind").asText() != "success") return@mapIndexedNotNull null
            val value = compileExpression(case.outcome.path("value"), variables, asValue = true)
            "((${effectiveConditions[index]}) and $resultName = ($value))"
        }
        val errorBranches = observed.cases.mapIndexedNotNull { index, case ->
            if (case.outcome.path("kind").asText() != "error") return@mapIndexedNotNull null
            case.outcome.path("error").asText() to effectiveConditions[index]
        }
        if (successBranches.isEmpty()) {
            throw UnsupportedRefinementException("Observed contract has no success outcome")
        }

        val baseSpecification = specificationCompiler.compile(model, bounds)
            .lineSequence()
            .filterNot { it.startsWith("run ") || it.startsWith("check ") }
            .joinToString("\n")
            .trimEnd()
        val implSuccess = "Observed_${sanitize(observed.operation)}_Success"
        val declarationsWithResult =
            listOf(parameterDeclarations, resultDeclaration).filter(String::isNotBlank).joinToString(", ")
        val quantifiedParameters = parameterDeclarations.ifBlank { "" }

        return buildString {
            appendLine(baseSpecification)
            appendLine()
            appendLine("// Observed Contract IR fact: ${observed.factId}")
            appendLine("pred $implSuccess[$declarationsWithResult] {")
            appendLine("  ${disjunction(successBranches)}")
            appendLine("}")
            appendLine()
            appendLine("assert Refinement_SpecPreImplDefined {")
            appendLine(quantify(quantifiedParameters, "$preCall implies (some $resultDeclaration | $implSuccess[$resultArguments])"))
            appendLine("}")
            appendLine()
            appendLine("assert Refinement_SuccessSatisfiesPost {")
            appendLine(
                quantify(
                    declarationsWithResult,
                    "($preCall and $implSuccess[$resultArguments]) implies $postCall",
                ),
            )
            appendLine("}")
            appendLine()
            appendLine("assert Refinement_SuccessRequiresPre {")
            appendLine(
                quantify(
                    declarationsWithResult,
                    "$implSuccess[$resultArguments] implies $preCall",
                ),
            )
            appendLine("}")
            appendLine()
            appendLine("assert Refinement_ErrorsAllowed {")
            val errorObligations = errorBranches.map { (label, condition) ->
                val error = errors.single { it.label == label }
                val predicate = errorPredicateName(contract, error)
                "($condition) implies $predicate[$parameterNames]"
            }
            appendLine(quantify(quantifiedParameters, conjunction(errorObligations)))
            appendLine("}")
            appendLine()
            appendLine("assert Refinement_Total {")
            appendLine(quantify(quantifiedParameters, disjunction(effectiveConditions)))
            appendLine("}")
            appendLine()
            appendLine("assert Refinement_ResultUnique {")
            val resultType = alloyDeclaration(result.valueType, nodesById)
            val first = "${resultName}1"
            val second = "${resultName}2"
            val firstArgs = listOf(parameterNames, first).filter(String::isNotBlank).joinToString(", ")
            val secondArgs = listOf(parameterNames, second).filter(String::isNotBlank).joinToString(", ")
            appendLine(
                quantify(
                    listOf(parameterDeclarations, "$first: $resultType", "$second: $resultType")
                        .filter(String::isNotBlank)
                        .joinToString(", "),
                    "($implSuccess[$firstArgs] and $implSuccess[$secondArgs]) implies $first = $second",
                ),
            )
            appendLine("}")
            appendLine()
            appendLine(
                if (parameterDeclarations.isBlank()) {
                    "pred RefinementInputs { ${disjunction(effectiveConditions)} }"
                } else {
                    "pred RefinementInputs { some $parameterDeclarations | ${disjunction(effectiveConditions)} }"
                },
            )
            val scope = "for ${bounds.globalScope} but ${bounds.intBitwidth} Int"
            appendLine("run RefinementInputs $scope")
            appendLine("check Refinement_SpecPreImplDefined $scope")
            appendLine("check Refinement_SuccessSatisfiesPost $scope")
            appendLine("check Refinement_SuccessRequiresPre $scope")
            appendLine("check Refinement_ErrorsAllowed $scope")
            appendLine("check Refinement_Total $scope")
            appendLine("check Refinement_ResultUnique $scope")
        }
    }

    private fun validateTypes(
        parameters: List<ModelNode>,
        result: ModelNode,
        observed: ObservedContract,
        nodesById: Map<String, ModelNode>,
    ) {
        if (parameters.map(ModelNode::label) != observed.parameters.map(ObservedParameter::name)) {
            throw UnsupportedRefinementException("Observed parameter names or order do not match the contract")
        }
        parameters.zip(observed.parameters).forEach { (expected, actual) ->
            if (!typesMatch(expected.valueType!!, actual.valueType, nodesById)) {
                throw UnsupportedRefinementException("Type mismatch for parameter ${expected.label}")
            }
        }
        if (!typesMatch(result.valueType!!, observed.resultType, nodesById)) {
            throw UnsupportedRefinementException("Type mismatch for result ${result.label}")
        }
    }

    private fun validateErrors(errors: List<ModelNode>, observed: ObservedContract) {
        val allowed = errors.map(ModelNode::label).toSet()
        val declared = observed.errorTypes.toSet()
        val returned = observed.cases
            .filter { it.outcome.path("kind").asText() == "error" }
            .map { it.outcome.path("error").asText() }
            .toSet()
        if (!declared.containsAll(returned)) {
            throw UnsupportedRefinementException("Observed case returns an undeclared error")
        }
        val unsupported = returned - allowed
        if (unsupported.isNotEmpty()) {
            throw UnsupportedRefinementException("Observed errors are not allowed by the contract: ${unsupported.sorted()}")
        }
    }

    private fun typesMatch(
        expected: ValueType,
        observed: ObservedValueType,
        nodesById: Map<String, ModelNode>,
    ): Boolean = when (expected.kind) {
        ValueKind.INT -> observed.kind == "int"
        ValueKind.BOOL -> observed.kind == "bool"
        ValueKind.STRING -> observed.kind == "string"
        ValueKind.ENUM -> {
            val type = nodesById[expected.typeId]
            observed.kind == "enum" && type != null && observed.members.toSet() == type.members.toSet()
        }
        ValueKind.SET, ValueKind.LIST ->
            observed.kind == expected.kind.wireValue &&
                expected.elementType != null &&
                observed.elementType != null &&
                typesMatch(expected.elementType, observed.elementType, nodesById)
    }

    private fun compileExpression(
        expression: JsonNode,
        variables: Map<String, String>,
        asValue: Boolean = false,
    ): String {
        return when (val op = expression.path("op").asText()) {
            "literal" -> when {
                expression.path("value").isBoolean ->
                    if (expression.path("value").asBoolean()) "no none" else "some none"
                else -> throw UnsupportedRefinementException("Unsupported observed literal")
            }
            "intLiteral" -> expression.path("value").asText().also {
                if (!it.matches(Regex("-?[0-9]+"))) {
                    throw UnsupportedRefinementException("Invalid integer literal: $it")
                }
            }
            "valueRef" -> variables[expression.path("name").asText()]
                ?: throw UnsupportedRefinementException(
                    "Unbound observed value: ${expression.path("name").asText()}",
                )
            else -> {
                val args = expression.path("args").map {
                    compileExpression(it, variables, asValue = op in valueOperators)
                }
                when (op) {
                    "not" -> "not (${args.single()})"
                    "and" -> args.joinToString(" and ", "(", ")")
                    "or" -> args.joinToString(" or ", "(", ")")
                    "eq" -> "(${args.requireArity(2)[0]}) = (${args[1]})"
                    "neq" -> "(${args.requireArity(2)[0]}) != (${args[1]})"
                    "lt" -> "(${args.requireArity(2)[0]}) < (${args[1]})"
                    "lte" -> "(${args.requireArity(2)[0]}) =< (${args[1]})"
                    "gt" -> "(${args.requireArity(2)[0]}) > (${args[1]})"
                    "gte" -> "(${args.requireArity(2)[0]}) >= (${args[1]})"
                    "add" -> "(${args.requireArity(2)[0]}).add[${args[1]}]"
                    "sub" -> "(${args.requireArity(2)[0]}).sub[${args[1]}]"
                    "mul" -> "(${args.requireArity(2)[0]}).mul[${args[1]}]"
                    else -> throw UnsupportedRefinementException("Unsupported observed expression: $op")
                }
            }
        }.let { compiled ->
            if (!asValue || expression.path("op").asText() !in booleanOperators) compiled else compiled
        }
    }

    private fun alloyDeclaration(type: ValueType, nodesById: Map<String, ModelNode>): String = when (type.kind) {
        ValueKind.INT -> "Int"
        ValueKind.BOOL -> "AiddBool"
        ValueKind.STRING -> "AiddString"
        ValueKind.ENUM -> nodeName(nodesById.getValue(type.typeId!!))
        ValueKind.SET -> "set ${alloyDeclaration(type.elementType!!, nodesById)}"
        ValueKind.LIST -> "seq ${alloyDeclaration(type.elementType!!, nodesById)}"
    }

    private fun nodeName(node: ModelNode): String = "${sanitize(node.type)}_${sanitize(node.label)}"

    private fun valueName(node: ModelNode): String =
        "${sanitize(node.label).replaceFirstChar(Char::lowercase)}_${Hashing.sha256(node.id).take(8)}"

    private fun contractBaseName(contract: ModelNode): String =
        "Contract_${sanitize(contract.label)}_${Hashing.sha256(contract.id).take(8)}"

    private fun errorPredicateName(contract: ModelNode, error: ModelNode): String =
        "${contractBaseName(contract)}_Error_${sanitize(error.label)}_${Hashing.sha256(error.id).take(8)}"

    private fun sanitize(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "Unnamed" }
        return if (sanitized.first().isDigit()) "_$sanitized" else sanitized
    }

    private fun conjunction(expressions: List<String>): String =
        expressions.ifEmpty { listOf("no none") }.joinToString(" and ", "(", ")")

    private fun disjunction(expressions: List<String>): String =
        expressions.ifEmpty { listOf("some none") }.joinToString(" or ", "(", ")")

    private fun quantify(declarations: String, body: String): String =
        if (declarations.isBlank()) "  $body" else "  all $declarations | ($body)"

    private fun List<String>.requireArity(expected: Int): List<String> {
        if (size != expected) {
            throw UnsupportedRefinementException("Expected $expected expression arguments, got $size")
        }
        return this
    }

    companion object {
        private val valueOperators = setOf(
            "eq", "neq", "lt", "lte", "gt", "gte", "add", "sub", "mul",
        )
        private val booleanOperators = setOf("not", "and", "or", "eq", "neq", "lt", "lte", "gt", "gte")
    }
}
