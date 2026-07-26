package dev.aidd.backport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.aidd.model.ClaimStatus
import dev.aidd.model.Hashing
import dev.aidd.model.ModelParser
import java.nio.file.Files
import java.nio.file.Path

data class DiffResult(
    val matched: List<String>,
    val missing: List<String>,
    val extra: List<String>,
    val contradictions: List<String>,
    val evidenceMissing: List<String>,
)

class BackportService(
    private val parser: ModelParser = ModelParser(),
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule(),
) {
    fun diff(observedPath: Path, intendedPath: Path): DiffResult {
        val observed = parser.parse(observedPath)
        val intended = parser.parse(intendedPath)
        val observedAccepted = observed.nodes.filter { it.status == ClaimStatus.ACCEPTED }
        val intendedAccepted = intended.nodes.filter { it.status == ClaimStatus.ACCEPTED }
        val observedIds = observedAccepted.map { it.id }.toSet()
        val intendedIds = intendedAccepted.map { it.id }.toSet()
        val observedById = observedAccepted.associateBy { it.id }
        val intendedById = intendedAccepted.associateBy { it.id }
        val sharedIds = observedIds intersect intendedIds
        val semanticMismatches = sharedIds.filter { id ->
            semanticFingerprint(observedById.getValue(id)) != semanticFingerprint(intendedById.getValue(id))
        }
        return DiffResult(
            matched = (sharedIds - semanticMismatches.toSet()).sorted(),
            missing = (intendedIds - observedIds).sorted(),
            extra = (observedIds - intendedIds).sorted(),
            contradictions = (
                semanticMismatches.map { "$it: semantic content differs" } +
                    observedAccepted.flatMap { node ->
                        node.relations["contradicts"].orEmpty().map { "${node.id} -> $it" }
                    }
                ).sorted(),
            evidenceMissing = observedAccepted
                .filter { it.evidence.isEmpty() && it.relations["evidencedBy"].isNullOrEmpty() }
                .map { it.id }
                .sorted(),
        )
    }

    fun validateFacts(factsPath: Path, modelPath: Path, repository: Path? = null): List<String> {
        val root = objectMapper.readTree(factsPath.toFile())
        val diagnostics = mutableListOf<String>()
        unexpectedFields(
            root,
            setOf("schemaVersion", "language", "extractor", "repositorySha256", "facts", "diagnostics"),
        ).forEach { diagnostics += "CodeFacts: unknown field $it" }
        if (!root.isObject || root.path("schemaVersion").asText() != "1.0") {
            diagnostics += "CodeFacts: unsupported or missing schemaVersion"
        }
        if (root.path("language").asText() !in setOf("kotlin", "typescript")) {
            diagnostics += "CodeFacts: language must be kotlin or typescript"
        }
        val extractor = root.path("extractor")
        if (!extractor.isObject ||
            extractor.path("name").asText() !in setOf("kotlin-compiler-psi", "typescript-compiler-api") ||
            extractor.path("version").asText().isBlank()
        ) {
            diagnostics += "CodeFacts: invalid or missing extractor identity"
        }
        if (!root.path("repositorySha256").asText().matches(Regex("[a-f0-9]{64}"))) {
            diagnostics += "CodeFacts: invalid repositorySha256"
        }
        val facts = root.path("facts")
        if (!facts.isArray) diagnostics += "CodeFacts: facts must be an array"
        val factIds = facts.takeIf(JsonNode::isArray)?.map { it.path("id").asText() }.orEmpty()
        factIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted().forEach {
            diagnostics += "CodeFacts: duplicate fact id $it"
        }
        val realRepository = repository?.toAbsolutePath()?.normalize()?.toRealPath()
        facts.takeIf(JsonNode::isArray)?.forEach { fact ->
            val id = fact.path("id").asText()
            unexpectedFields(
                fact,
                setOf("id", "kind", "name", "qualifiedName", "status", "basis", "source", "details"),
            ).forEach { diagnostics += "$id: unknown fact field $it" }
            if (!id.startsWith("urn:aidd:")) diagnostics += "$id: invalid fact id"
            if (fact.path("status").asText() != "accepted" || fact.path("basis").asText() != "observed") {
                diagnostics += "$id: deterministic facts must be accepted/observed"
            }
            val source = fact.path("source")
            unexpectedFields(
                source,
                setOf("path", "startLine", "startColumn", "endLine", "endColumn", "sha256"),
            ).forEach { diagnostics += "$id: unknown source field $it" }
            val hash = source.path("sha256").asText()
            if (!source.isObject || !hash.matches(Regex("[a-f0-9]{64}"))) {
                diagnostics += "$id: invalid source evidence"
            } else if (source.path("startLine").asInt() < 1 ||
                source.path("startColumn").asInt() < 1 ||
                source.path("endLine").asInt() < source.path("startLine").asInt()
            ) {
                diagnostics += "$id: invalid source span"
            }
            if (realRepository != null && source.path("path").isTextual) {
                val sourcePath = realRepository.resolve(source.path("path").asText()).normalize()
                if (!sourcePath.startsWith(realRepository) || !Files.exists(sourcePath) ||
                    Files.isSymbolicLink(sourcePath) || !sourcePath.toRealPath().startsWith(realRepository)
                ) {
                    diagnostics += "$id: source path is missing, symlinked, or outside repository"
                } else if (Hashing.sha256(sourcePath) != hash) {
                    diagnostics += "$id: source hash mismatch"
                }
            }
        }
        root.path("diagnostics").takeIf(JsonNode::isArray)?.forEach {
            val severity = it.path("severity").asText(it.path("status").asText()).lowercase()
            if (severity in setOf("error", "unsupported")) {
                diagnostics += "Extractor ${it.path("code").asText("diagnostic")}: ${it.path("message").asText()}"
            }
        }
        val model = parser.parse(modelPath)
        diagnostics += model.nodes
            .filter { it.status == ClaimStatus.ACCEPTED && it.basis.wireValue == "observed" }
            .flatMap { node ->
                val references = node.relations["evidencedBy"].orEmpty()
                when {
                    references.isEmpty() -> listOf("${node.id}: accepted observed claim has no evidencedBy fact")
                    else -> references.filterNot(factIds::contains).map {
                        "${node.id}: missing CodeFact $it"
                    }
                }
            }
        return diagnostics.sorted()
    }

    fun renderFacts(factsPath: Path): String {
        val root = objectMapper.readTree(factsPath.toFile())
        val language = root.path("language").asText("unknown")
        val facts = root.path("facts").takeIf(JsonNode::isArray)?.toList().orEmpty()
            .filter { it.path("status").asText() == "accepted" }
            .sortedWith(compareBy({ it.path("kind").asText() }, { it.path("id").asText() }))
        return buildString {
            appendLine("# As-built Specification")
            appendLine()
            appendLine("> Deterministically rendered from accepted `$language` compiler and contract facts.")
            facts.groupBy { it.path("kind").asText("Unknown") }.toSortedMap().forEach { (kind, grouped) ->
                appendLine()
                appendLine("## $kind")
                grouped.forEach { fact ->
                    appendLine()
                    appendLine("- **${fact.path("name").asText(fact.path("id").asText())}** (`${fact.path("id").asText()}`)")
                    appendLine("  - Qualified name: `${fact.path("qualifiedName").asText()}`")
                    val source = fact.path("source")
                    if (source.isObject) {
                        appendLine(
                            "  - Evidence: `${source.path("path").asText()}:${source.path("startLine").asInt()}` " +
                                "SHA-256 `${source.path("sha256").asText()}`",
                        )
                    }
                    val details = fact.path("details")
                    if (details.isObject && details.size() > 0) {
                        appendLine("  - Details: `${objectMapper.writeValueAsString(details)}`")
                    }
                }
            }
        }
    }

    fun diffAsJson(result: DiffResult): ObjectNode = objectMapper.createObjectNode().apply {
        putArrayValues("matched", result.matched)
        putArrayValues("missing", result.missing)
        putArrayValues("extra", result.extra)
        putArrayValues("contradictions", result.contradictions)
        putArrayValues("evidenceMissing", result.evidenceMissing)
    }

    private fun ObjectNode.putArrayValues(name: String, values: List<String>): ArrayNode =
        putArray(name).also { array -> values.forEach(array::add) }

    private fun semanticFingerprint(node: dev.aidd.model.ModelNode): String {
        val canonical = objectMapper.createObjectNode().apply {
            put("type", node.type)
            put("label", node.label)
            set<JsonNode>("expression", node.expression ?: objectMapper.nullNode())
            set<JsonNode>("relations", objectMapper.valueToTree(node.relations.toSortedMap()))
        }
        return objectMapper.writeValueAsString(canonical)
    }

    private fun unexpectedFields(node: JsonNode, allowed: Set<String>): List<String> =
        if (!node.isObject) {
            listOf("<non-object>")
        } else {
            node.fieldNames().asSequence().filterNot(allowed::contains).toList().sorted()
        }
}
