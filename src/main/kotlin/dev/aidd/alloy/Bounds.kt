package dev.aidd.alloy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path

data class Bounds(
    val globalScope: Int,
    val intBitwidth: Int,
    val maxTraceSteps: Int,
    val approved: Boolean,
    val approvedBy: String? = null,
    val decisionId: String? = null,
    val maxListLength: Int = 3,
) {
    init {
        require(globalScope > 0) { "globalScope must be positive" }
        require(intBitwidth in 1..32) { "intBitwidth must be between 1 and 32" }
        require(maxTraceSteps > 0) { "maxTraceSteps must be positive" }
        require(maxListLength > 0) { "maxListLength must be positive" }
        require(!approved || !approvedBy.isNullOrBlank()) { "approved bounds require approvedBy" }
        require(!approved || decisionId?.startsWith("urn:aidd:") == true) {
            "approved bounds require an urn:aidd decisionId"
        }
    }

    companion object {
        fun defaultExploration(): Bounds = Bounds(3, 4, 10, false)

        fun read(path: Path): Bounds {
            val root = ObjectMapper().registerKotlinModule().readTree(path.toFile())
            return Bounds(
                globalScope = root.path("globalScope").asInt(),
                intBitwidth = root.path("intBitwidth").asInt(),
                maxTraceSteps = root.path("maxTraceSteps").asInt(),
                approved = root.path("approved").asBoolean(false),
                approvedBy = root.path("approvedBy").takeIf { it.isTextual }?.asText(),
                decisionId = root.path("decisionId").takeIf { it.isTextual }?.asText(),
                maxListLength = root.path("maxListLength").takeIf { it.isInt }?.asInt() ?: 3,
            )
        }
    }
}
