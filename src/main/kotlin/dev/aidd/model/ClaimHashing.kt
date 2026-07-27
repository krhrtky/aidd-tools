package dev.aidd.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object ClaimHashing {
    private val mapper = ObjectMapper().registerKotlinModule()

    fun sha256(node: ModelNode): String = Hashing.sha256(
        mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                put("id", node.id)
                put("type", node.type)
                put("label", node.label)
                put("basis", node.basis.wireValue)
                node.generatedBy?.let { put("generatedBy", it) }
                set<JsonNode>("evidence", mapper.valueToTree(node.evidence))
                set<JsonNode>("relations", mapper.valueToTree(node.relations.toSortedMap()))
                node.expression?.let { set<JsonNode>("expression", canonicalize(it)) }
                node.valueType?.let { set<JsonNode>("valueType", mapper.valueToTree(it)) }
                if (node.members.isNotEmpty()) set<JsonNode>("members", mapper.valueToTree(node.members))
                node.total?.let { put("total", it) }
            },
        ),
    )

    private fun canonicalize(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { result ->
            node.fieldNames().asSequence().toList().sorted().forEach { name ->
                result.set<JsonNode>(name, canonicalize(node.get(name)))
            }
        }
        node.isArray -> mapper.createArrayNode().also { result ->
            node.forEach { result.add(canonicalize(it)) }
        }
        else -> node.deepCopy()
    }
}
