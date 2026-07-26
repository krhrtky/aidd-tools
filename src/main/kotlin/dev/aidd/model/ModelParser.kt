package dev.aidd.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path

class ModelParser(
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule(),
) {
    fun parse(path: Path): AiddModel = parse(path.toFile().readText())

    fun parse(content: String): AiddModel {
        val root = objectMapper.readTree(content)
        require(root.isObject) { "Model root must be an object" }
        root.requireOnlyFields(setOf("@context", "schemaVersion", "specId", "@graph"), "model")
        val context = root.requiredText("@context")
        val schemaVersion = root.requiredText("schemaVersion")
        val specId = root.requiredText("specId")
        val graph = root.get("@graph") ?: error("Missing @graph")
        require(graph.isArray) { "@graph must be an array" }
        val nodes = graph.map(::parseNode).sortedBy(ModelNode::id)
        return AiddModel(context, schemaVersion, specId, nodes)
    }

    private fun parseNode(node: JsonNode): ModelNode {
        require(node.isObject) { "Graph entries must be objects" }
        node.requireOnlyFields(
            setOf(
                "@id", "@type", "label", "status", "basis", "generatedBy", "evidence", "expression",
            ) + Vocabulary.relations,
            "graph node",
        )
        node.get("label")?.let { require(it.isTextual) { "label must be a string" } }
        node.get("generatedBy")?.let { require(it.isTextual) { "generatedBy must be a string" } }
        node.get("evidence")?.let { require(it.isArray) { "evidence must be an array" } }
        node.get("expression")?.let { require(it.isObject) { "expression must be an object" } }
        val evidence = node.path("evidence").takeIf(JsonNode::isArray)?.map(::parseEvidence).orEmpty()
        val relations = Vocabulary.relations.associateWith { relation ->
            val value = node.get(relation)
            when {
                value == null || value.isNull -> emptyList()
                value.isArray -> value.map {
                    if (it.isTextual) it.asText() else it.requiredText("@id")
                }
                else -> error("$relation must be an array of references")
            }
        }.filterValues(List<String>::isNotEmpty)
        node.get("expression")?.let(::validateExpressionFields)
        return ModelNode(
            id = node.requiredText("@id"),
            type = node.requiredText("@type"),
            label = node.path("label").asText(node.requiredText("@id")),
            status = ClaimStatus.fromWire(node.requiredText("status")),
            basis = ClaimBasis.fromWire(node.requiredText("basis")),
            evidence = evidence,
            relations = relations,
            expression = node.get("expression"),
            generatedBy = node.path("generatedBy").takeIf(JsonNode::isTextual)?.asText(),
        )
    }

    private fun validateExpressionFields(expression: JsonNode) {
        require(expression.isObject) { "expression must be an object" }
        expression.requireOnlyFields(
            setOf("op", "id", "name", "value", "variable", "domain", "body", "args"),
            "expression",
        )
        expression.path("args").takeIf(JsonNode::isArray)?.forEach(::validateExpressionFields)
        expression.path("domain").takeIf(JsonNode::isObject)?.let(::validateExpressionFields)
        expression.path("body").takeIf(JsonNode::isObject)?.let(::validateExpressionFields)
    }

    private fun parseEvidence(node: JsonNode): Evidence {
        node.requireOnlyFields(
            setOf("path", "startLine", "startColumn", "endLine", "endColumn", "sha256"),
            "evidence",
        )
        return Evidence(
            path = node.requiredText("path"),
            startLine = node.requiredPositiveInt("startLine"),
            startColumn = node.requiredPositiveInt("startColumn"),
            endLine = node.requiredPositiveInt("endLine"),
            endColumn = node.requiredPositiveInt("endColumn"),
            sha256 = node.requiredText("sha256"),
        )
    }
}

private fun JsonNode.requireOnlyFields(allowed: Set<String>, location: String) {
    val unknown = fieldNames().asSequence().filterNot(allowed::contains).toList().sorted()
    require(unknown.isEmpty()) { "Unknown fields in $location: ${unknown.joinToString()}" }
}

private fun JsonNode.requiredText(name: String): String {
    val value = get(name) ?: error("Missing $name")
    require(value.isTextual && value.asText().isNotBlank()) { "$name must be a non-empty string" }
    return value.asText()
}

private fun JsonNode.requiredPositiveInt(name: String): Int {
    val value = get(name) ?: error("Missing $name")
    require(value.canConvertToInt() && value.asInt() > 0) { "$name must be a positive integer" }
    return value.asInt()
}
