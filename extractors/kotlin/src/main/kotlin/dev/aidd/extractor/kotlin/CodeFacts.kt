package dev.aidd.extractor.kotlin

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class CodeFacts(
    val schemaVersion: String = "1.0",
    val language: String = "kotlin",
    val extractor: ExtractorIdentity = ExtractorIdentity(),
    val repositorySha256: String,
    val facts: List<CodeFact>,
    val diagnostics: List<Diagnostic>,
)

data class ExtractorIdentity(
    val name: String = "kotlin-compiler-psi",
    val version: String = "2.3.21",
)

data class CodeFact(
    val id: String,
    val kind: String,
    val name: String,
    val qualifiedName: String,
    val status: String = "accepted",
    val basis: String = "observed",
    val source: SourceLocation,
    val details: Map<String, Any?> = emptyMap(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Diagnostic(
    val severity: String,
    val code: String,
    val message: String,
    val source: SourceLocation? = null,
    val details: Map<String, Any?> = emptyMap(),
)

data class SourceLocation(
    val path: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val sha256: String,
)

object CodeFactsJson {
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .build()

    fun write(value: CodeFacts): String = mapper.writeValueAsString(value) + "\n"

    fun writeTo(path: Path, value: CodeFacts) {
        val normalized = path.toAbsolutePath().normalize()
        var current = normalized.root
        var depth = 0
        normalized.forEach { component ->
            depth += 1
            current = current.resolve(component)
            require(depth == 1 || !Files.isSymbolicLink(current)) { "Output path contains a symbolic link: $current" }
        }
        val parent = normalized.parent
        Files.createDirectories(parent)
        require(!Files.isSymbolicLink(normalized)) { "Output file is a symbolic link: $normalized" }
        val temporary = Files.createTempFile(parent, ".code-facts-", ".json")
        try {
            Files.writeString(temporary, write(value))
            try {
                Files.move(
                    temporary,
                    normalized,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
