package dev.aidd.refinement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path

data class ObservedContract(
    val factId: String,
    val qualifiedName: String,
    val contractIds: List<String>,
    val operation: String,
    val parameters: List<ObservedParameter>,
    val resultType: ObservedValueType,
    val errorTypes: List<String>,
    val cases: List<ObservedCase>,
)

data class ObservedParameter(
    val name: String,
    val valueType: ObservedValueType,
)

data class ObservedValueType(
    val kind: String,
    val name: String? = null,
    val members: List<String> = emptyList(),
    val elementType: ObservedValueType? = null,
)

data class ObservedCase(
    val condition: JsonNode,
    val outcome: JsonNode,
)

class UnsupportedRefinementException(message: String) : RuntimeException(message)

class ObservedContractParser(
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule(),
) {
    fun parse(factsPath: Path, operation: String): ObservedContract {
        val root = mapper.readTree(factsPath.toFile())
        require(root.path("schemaVersion").asText() == "1.0") { "Unsupported CodeFacts schemaVersion" }
        val matching = root.path("facts")
            .filter {
                it.path("kind").asText() == "observedContract" &&
                    (
                        it.path("qualifiedName").asText() == operation ||
                            it.path("details").path("operation").asText() == operation
                        )
            }
        require(matching.size == 1) {
            "Expected exactly one observedContract for operation '$operation', found ${matching.size}"
        }
        val fact = matching.single()
        require(fact.path("status").asText() == "accepted" && fact.path("basis").asText() == "observed") {
            "Observed contract must be accepted/observed"
        }
        val details = fact.path("details")
        require(details.path("schemaVersion").asText() == "1.0") {
            "Unsupported Observed Contract IR schemaVersion"
        }
        val cases = details.path("cases").mapIndexed { index, case ->
            val condition = case.get("when")
                ?: throw UnsupportedRefinementException("Case $index has no condition")
            val outcome = case.get("outcome")
                ?: throw UnsupportedRefinementException("Case $index has no outcome")
            ObservedCase(condition, outcome)
        }
        if (cases.isEmpty()) {
            throw UnsupportedRefinementException("Observed contract must contain at least one case")
        }
        return ObservedContract(
            factId = fact.path("id").asText(),
            qualifiedName = fact.path("qualifiedName").asText(),
            contractIds = details.path("contractIds").map(JsonNode::asText),
            operation = details.path("operation").asText(),
            parameters = details.path("parameters").map {
                ObservedParameter(
                    name = it.path("name").asText(),
                    valueType = parseType(it.path("valueType")),
                )
            },
            resultType = parseType(details.path("resultType")),
            errorTypes = details.path("errorTypes").map(JsonNode::asText),
            cases = cases,
        )
    }

    private fun parseType(node: JsonNode): ObservedValueType {
        val kind = node.path("kind").asText()
        if (kind !in setOf("int", "bool", "string", "enum", "set", "list")) {
            throw UnsupportedRefinementException("Unsupported observed value type: $kind")
        }
        return ObservedValueType(
            kind = kind,
            name = node.path("name").takeIf(JsonNode::isTextual)?.asText(),
            members = node.path("members").takeIf(JsonNode::isArray)?.map(JsonNode::asText).orEmpty(),
            elementType = node.path("elementType").takeIf(JsonNode::isObject)?.let(::parseType),
        )
    }
}
