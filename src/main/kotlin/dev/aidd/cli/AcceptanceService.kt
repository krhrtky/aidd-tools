package dev.aidd.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aidd.model.ClaimHashing
import dev.aidd.model.ClaimStatus
import dev.aidd.model.Hashing
import dev.aidd.model.ModelParser
import dev.aidd.model.ModelValidator
import java.nio.file.Files
import java.nio.file.Path

data class AcceptanceResult(
    val modelPath: Path,
    val manifestPath: Path,
)

class AcceptanceService(
    private val parser: ModelParser = ModelParser(),
    private val validator: ModelValidator = ModelValidator(),
) {
    fun accept(
        modelPath: Path,
        decisionPath: Path,
        outputPath: Path,
    ): AcceptanceResult {
        val sourceModel = modelPath.toAbsolutePath().normalize()
        val sourceDecision = decisionPath.toAbsolutePath().normalize()
        val targetModel = outputPath.toAbsolutePath().normalize()
        require(sourceModel != targetModel) { "accept output must not overwrite the candidate model" }
        require(targetModel.fileName.toString().endsWith(".jsonld")) { "accept output must be a .jsonld file" }
        require(sourceModel.parent == targetModel.parent) {
            "accept output must be in the candidate model directory so existing evidence paths remain valid"
        }
        require(Files.isRegularFile(sourceDecision) && !Files.isSymbolicLink(sourceDecision)) {
            "decision must be a regular non-symlink file"
        }

        val validation = validator.validate(sourceModel)
        require(validation.isValid && !validation.requiresHumanReview) {
            "candidate model must pass deterministic validation before acceptance"
        }
        val model = validation.model!!
        val decisionRoot = JsonOutput.mapper.readTree(sourceDecision.toFile())
        val decision = parseDecision(decisionRoot)
        require(model.nodes.none { it.id == decision.decisionId }) {
            "decisionId already exists in the model: ${decision.decisionId}"
        }

        val nodesById = model.nodes.associateBy { it.id }
        val claimIds = decision.claims.toSet()
        val assumptionIds = decision.assumptions.toSet()
        val approvedIds = claimIds + assumptionIds
        require(approvedIds.isNotEmpty()) { "decision must approve at least one claim or assumption" }
        require(claimIds.size == decision.claims.size && assumptionIds.size == decision.assumptions.size) {
            "decision claim lists must not contain duplicates"
        }
        require((claimIds intersect assumptionIds).isEmpty()) {
            "a claim cannot be listed as both a claim and an assumption"
        }
        approvedIds.sorted().forEach { id ->
            val node = nodesById[id] ?: error("decision refers to missing claim: $id")
            require(node.status == ClaimStatus.CANDIDATE) {
                "only candidate claims can be accepted: $id is ${node.status.wireValue}"
            }
            require(node.type != "HumanDecision") { "HumanDecision nodes cannot be promoted by accept" }
        }
        claimIds.sorted().forEach { id ->
            require(nodesById.getValue(id).type != "Assumption") {
                "assumption must be listed explicitly in assumptions: $id"
            }
        }
        assumptionIds.sorted().forEach { id ->
            require(nodesById.getValue(id).type == "Assumption") {
                "assumptions may contain only Assumption nodes: $id"
            }
        }
        requireSemanticDependenciesAreExplicit(approvedIds, nodesById)

        val sourceModelHash = Hashing.sha256(sourceModel)
        val approvedHashes = approvedIds.sorted().associateWith { ClaimHashing.sha256(nodesById.getValue(it)) }
        val decisionHash = Hashing.sha256(sourceDecision)
        val decisionDirectory = targetModel.parent.resolve(".aidd-decisions")
        val decisionArtifact = decisionDirectory.resolve(
            "${sanitize(decision.decisionId)}-${decisionHash.take(12)}.json",
        )
        SafePaths.copy(sourceDecision, decisionArtifact)
        val decisionEvidence = evidence(decisionArtifact, targetModel.parent)

        val root = JsonOutput.mapper.readTree(sourceModel.toFile()) as ObjectNode
        root.put("schemaVersion", "1.1")
        val promotedNodes = root.path("@graph").map { raw ->
            (raw.deepCopy<JsonNode>() as ObjectNode).apply {
                if (path("@id").asText() in approvedIds) put("status", "accepted")
            }
        }.toMutableList()
        promotedNodes += JsonOutput.mapper.createObjectNode().apply {
            put("@id", decision.decisionId)
            put("@type", "HumanDecision")
            put("label", "Acceptance by ${decision.approvedBy}")
            put("status", "accepted")
            put("basis", "stated")
            put("generatedBy", "human")
            putArray("evidence").add(decisionEvidence)
            putArray("defines").also { array -> claimIds.sorted().forEach(array::add) }
            putArray("constrains").also { array -> assumptionIds.sorted().forEach(array::add) }
            putObject("approvedClaimHashes").also { hashes ->
                approvedHashes.forEach(hashes::put)
            }
            put("sourceModelSha256", sourceModelHash)
        }
        root.set<ArrayNode>(
            "@graph",
            JsonOutput.mapper.createArrayNode().also { graph ->
                promotedNodes.sortedBy { it.path("@id").asText() }.forEach(graph::add)
            },
        )
        JsonOutput.writeNode(targetModel, root)

        val acceptedValidation = validator.validate(targetModel)
        require(acceptedValidation.isValid && !acceptedValidation.requiresHumanReview) {
            "generated accepted model failed validation: " +
                acceptedValidation.diagnostics.joinToString { it.code }
        }
        val manifestPath = targetModel.resolveSibling(
            targetModel.fileName.toString().removeSuffix(".jsonld") + ".acceptance.json",
        )
        val manifest = JsonOutput.mapper.createObjectNode().apply {
            put("schemaVersion", "1.0")
            put("decisionId", decision.decisionId)
            put("approvedBy", decision.approvedBy)
            put("sourceModelSha256", sourceModelHash)
            put("acceptedModelSha256", Hashing.sha256(targetModel))
            put("decisionSha256", decisionHash)
            put("decisionEvidence", targetModel.parent.relativize(decisionArtifact).toString())
            putArray("claims").also { array -> claimIds.sorted().forEach(array::add) }
            putArray("assumptions").also { array -> assumptionIds.sorted().forEach(array::add) }
            putObject("approvedClaimHashes").also { hashes ->
                approvedHashes.forEach(hashes::put)
            }
        }
        JsonOutput.writeNode(manifestPath, manifest)
        return AcceptanceResult(targetModel, manifestPath)
    }

    private fun parseDecision(root: JsonNode): AcceptanceDecision {
        require(root.isObject) { "decision root must be an object" }
        val allowed = setOf("schemaVersion", "decisionId", "approvedBy", "claims", "assumptions")
        val unknown = root.fieldNames().asSequence().filterNot(allowed::contains).toList().sorted()
        require(unknown.isEmpty()) { "unknown decision fields: ${unknown.joinToString()}" }
        require(root.path("schemaVersion").asText() == "1.0") { "decision schemaVersion must be 1.0" }
        val decisionId = root.requiredText("decisionId")
        require(decisionId.startsWith("urn:aidd:")) { "decisionId must start with urn:aidd:" }
        val approvedBy = root.requiredText("approvedBy")
        return AcceptanceDecision(
            decisionId = decisionId,
            approvedBy = approvedBy,
            claims = root.requiredStringArray("claims"),
            assumptions = root.requiredStringArray("assumptions"),
        )
    }

    private fun requireSemanticDependenciesAreExplicit(
        approvedIds: Set<String>,
        nodesById: Map<String, dev.aidd.model.ModelNode>,
    ) {
        val semanticRelations = setOf(
            "defines",
            "constrains",
            "derivesFrom",
            "dependsOn",
            "transitionsFrom",
            "transitionsTo",
            "accepts",
            "returns",
            "mayFailWith",
        )
        approvedIds.sorted().forEach { id ->
            val node = nodesById.getValue(id)
            val relationDependencies = node.relations
                .filterKeys(semanticRelations::contains)
                .values
                .flatten()
            val expressionDependencies = buildSet {
                collectExpressionReferences(node.expression, this)
            }
            (relationDependencies + expressionDependencies)
                .filter { dependency -> nodesById[dependency]?.status == ClaimStatus.CANDIDATE }
                .filterNot(approvedIds::contains)
                .sorted()
                .forEach { dependency ->
                    error("candidate dependency must be explicitly approved: $id -> $dependency")
                }
        }
    }

    private fun collectExpressionReferences(node: JsonNode?, references: MutableSet<String>) {
        if (node == null || !node.isObject) return
        if (node.path("op").asText() in setOf("ref", "valueRef") && node.path("id").isTextual) {
            references += node.path("id").asText()
        }
        node.path("args").takeIf(JsonNode::isArray)?.forEach { collectExpressionReferences(it, references) }
        node.path("domain").takeIf(JsonNode::isObject)?.let { collectExpressionReferences(it, references) }
        node.path("body").takeIf(JsonNode::isObject)?.let { collectExpressionReferences(it, references) }
    }

    private fun evidence(path: Path, baseDirectory: Path): ObjectNode {
        val text = Files.readString(path)
        val lines = text.split("\n")
        val lastContentLine = if (lines.lastOrNull().isNullOrEmpty() && lines.size > 1) lines.dropLast(1) else lines
        return JsonOutput.mapper.createObjectNode().apply {
            put("path", baseDirectory.relativize(path).toString())
            put("startLine", 1)
            put("startColumn", 1)
            put("endLine", lastContentLine.size.coerceAtLeast(1))
            put("endColumn", (lastContentLine.lastOrNull()?.length ?: 0) + 1)
            put("sha256", Hashing.sha256(path))
        }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "decision" }
}

private data class AcceptanceDecision(
    val decisionId: String,
    val approvedBy: String,
    val claims: List<String>,
    val assumptions: List<String>,
)

private fun JsonNode.requiredText(name: String): String {
    val value = get(name)
    require(value != null && value.isTextual && value.asText().isNotBlank()) {
        "$name must be a non-empty string"
    }
    return value.asText()
}

private fun JsonNode.requiredStringArray(name: String): List<String> {
    val value = get(name)
    require(value != null && value.isArray && value.all { it.isTextual && it.asText().isNotBlank() }) {
        "$name must be an array of non-empty strings"
    }
    return value.map(JsonNode::asText)
}
