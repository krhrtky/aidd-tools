package dev.aidd.alloy

import com.fasterxml.jackson.databind.JsonNode
import dev.aidd.model.AiddModel
import dev.aidd.model.ClaimStatus
import dev.aidd.model.Hashing
import dev.aidd.model.ModelNode

class AlloyCompiler {
    fun compile(model: AiddModel, bounds: Bounds): String {
        val accepted = model.nodes.filter { it.status == ClaimStatus.ACCEPTED }
        val names = allocateNames(accepted)
        val lines = mutableListOf<String>()
        lines += "module aidd_${sanitize(model.specId)}"
        lines += ""
        lines += "abstract sig AiddEntity {}"
        lines += "abstract sig AiddType {}"
        lines += "abstract sig AiddState {}"
        lines += "abstract sig AiddOperation {}"
        lines += "abstract sig AiddTransition { from: one AiddState, to: one AiddState }"
        lines += ""

        accepted.sortedBy(ModelNode::id).forEach { node ->
            val name = names.getValue(node.id)
            when (node.type) {
                "Entity" -> lines += "one sig $name extends AiddEntity {}"
                "Type" -> lines += "one sig $name extends AiddType {}"
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
        val transitions = accepted.filter { it.type == "Transition" }
        val needsTrace = transitions.isNotEmpty() ||
            accepted.any { it.expression?.containsTemporalOperator() == true || it.expression?.containsCurrent() == true }
        if (needsTrace) {
            lines += ""
            lines += "one sig AiddRuntime { var current: one AiddState }"
            val targetStateIds = transitions.flatMap { it.relations["transitionsTo"].orEmpty() }.toSet()
            val initialStates = accepted
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

        val expressionNodes = accepted.filter { it.expression != null }
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

    private fun compileExpression(expression: JsonNode, names: Map<String, String>): String {
        val op = expression.path("op").asText()
        if (op == "literal") {
            val value = expression.get("value")
            return when {
                value.isBoolean -> if (value.asBoolean()) "some univ" else "no univ"
                value.isNumber -> value.asText()
                else -> "\"${value.asText().replace("\"", "\\\"")}\""
            }
        }
        if (op == "ref") {
            return names[expression.path("id").asText()]
                ?: sanitize(expression.path("id").asText().substringAfterLast(':'))
        }
        if (op == "variable") {
            return sanitize(expression.path("name").asText())
        }
        if (op == "current") {
            return "AiddRuntime.current"
        }
        val args = expression.path("args").map { compileExpression(it, names) }
        return when (op) {
            "not" -> "not (${args.single()})"
            "and" -> args.joinToString(" and ", "(", ")")
            "or" -> args.joinToString(" or ", "(", ")")
            "implies" -> "(${args.requireArity(2)[0]}) implies (${args[1]})"
            "eq" -> "(${args.requireArity(2)[0]}) = (${args[1]})"
            "neq" -> "(${args.requireArity(2)[0]}) != (${args[1]})"
            "lt" -> "(${args.requireArity(2)[0]}) < (${args[1]})"
            "lte" -> "(${args.requireArity(2)[0]}) =< (${args[1]})"
            "gt" -> "(${args.requireArity(2)[0]}) > (${args[1]})"
            "gte" -> "(${args.requireArity(2)[0]}) >= (${args[1]})"
            "in" -> "(${args.requireArity(2)[0]}) in (${args[1]})"
            "all" -> {
                val variable = sanitize(expression.path("variable").asText())
                val domain = compileExpression(expression.path("domain"), names)
                val body = compileExpression(expression.path("body"), names)
                "all $variable: $domain | ($body)"
            }
            "some" -> "some (${args.single()})"
            "no" -> "no (${args.single()})"
            "one" -> "one (${args.single()})"
            "always" -> "always (${args.single()})"
            "eventually" -> "eventually (${args.single()})"
            "next" -> "after (${args.single()})"
            "until" -> "(${args.requireArity(2)[0]}) until (${args[1]})"
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
