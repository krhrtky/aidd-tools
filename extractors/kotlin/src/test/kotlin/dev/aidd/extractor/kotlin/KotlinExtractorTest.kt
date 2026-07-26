package dev.aidd.extractor.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KotlinExtractorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extracts declarations contracts guards transitions calls tests and documentation links`() {
        val repository = tempDir.resolve("repo")
        write(
            repository.resolve("src/main/kotlin/orders/Order.kt"),
            """
            package orders

            /** @aidd.requirement REQ-1 */
            data class Order(val id: String, val note: String?)

            sealed interface OrderState {
                data object Draft : OrderState
                data object Confirmed : OrderState
            }

            enum class Role { CUSTOMER, ADMIN }

            /** @aidd.verifies INV-1 */
            fun confirm(order: Order, state: OrderState): OrderState {
                require(order.id.isNotBlank()) { "id is required" }
                check(state is OrderState.Draft)
                val next = when (state) {
                    OrderState.Draft -> OrderState.Confirmed
                    else -> throw IllegalStateException("already confirmed")
                }
                audit(order.id)
                return next
            }

            private fun audit(id: String) = Unit
            """.trimIndent(),
        )
        write(
            repository.resolve("src/test/kotlin/orders/OrderTest.kt"),
            """
            package orders

            import kotlin.test.Test
            import kotlin.test.assertEquals

            class OrderTest {
                /** @aidd.verifies REQ-1 */
                @Test
                fun confirmsDraft() {
                    assertEquals(
                        OrderState.Confirmed,
                        confirm(Order("1", null), OrderState.Draft),
                    )
                }
            }
            """.trimIndent(),
        )

        val result = KotlinExtractor().extract(repository, allowBuildTool = false)

        assertEquals("1.0", result.schemaVersion)
        assertEquals("kotlin", result.language)
        assertTrue(result.repositorySha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(result.facts.any { it.kind == "type" && it.qualifiedName == "orders.Order" && it.details["modality"] == "data" })
        assertTrue(result.facts.any { it.kind == "type" && it.qualifiedName == "orders.OrderState" && it.details["modality"] == "sealed" })
        assertTrue(result.facts.any { it.kind == "type" && it.qualifiedName == "orders.Role" && it.details["modality"] == "enum" })
        assertTrue(result.facts.any { it.kind == "callable" && it.qualifiedName == "orders.confirm" })
        assertTrue(result.facts.any { it.kind == "guard" && it.details["guardKind"] == "require" })
        assertTrue(result.facts.any { it.kind == "guard" && it.details["guardKind"] == "check" })
        assertTrue(result.facts.any { it.kind == "throw" })
        assertTrue(result.facts.any { it.kind == "transition" })
        assertTrue(result.facts.any { it.kind == "assignment" && it.name == "next" })
        assertTrue(result.facts.any { it.kind == "call" && it.name == "audit" })
        assertTrue(result.facts.any { it.kind == "test" && it.name == "confirmsDraft" })
        assertTrue(result.facts.any { it.kind == "assertion" && it.name == "assertEquals" })
        assertTrue(result.facts.any { it.kind == "trace-link" && it.details["tag"] == "aidd.requirement" && it.details["target"] == "REQ-1" })
        assertTrue(result.facts.any { it.kind == "trace-link" && it.details["tag"] == "aidd.verifies" && it.details["target"] == "INV-1" })
        assertFalse(result.facts.any { it.qualifiedName == "orders.audit" && it.kind == "callable" })
        assertTrue(result.facts.all { it.status == "accepted" && it.basis == "observed" })
    }

    @Test
    fun `is byte stable and hashes repository independently of absolute path`() {
        val first = tempDir.resolve("first")
        val second = tempDir.resolve("second")
        val source = "package sample\npublic fun value(input: String?): String? = input\n"
        write(first.resolve("Value.kt"), source)
        write(second.resolve("Value.kt"), source)

        val firstResult = KotlinExtractor().extract(first, allowBuildTool = false)
        val secondResult = KotlinExtractor().extract(second, allowBuildTool = false)

        assertEquals(firstResult.repositorySha256, secondResult.repositorySha256)
        assertEquals(
            CodeFactsJson.write(firstResult),
            CodeFactsJson.write(secondResult),
        )
    }

    @Test
    fun `reports unsupported when public inferred types need a semantic classpath`() {
        val repository = tempDir.resolve("repo")
        write(
            repository.resolve("src/main/kotlin/sample/Service.kt"),
            """
            package sample
            import external.Library
            fun service() = Library.create()
            """.trimIndent(),
        )

        val result = KotlinExtractor().extract(repository, allowBuildTool = false)

        assertTrue(
            result.diagnostics.any {
                it.severity == "UNSUPPORTED" &&
                    it.code == "SEMANTIC_CLASSPATH_REQUIRED" &&
                    it.source?.path == "src/main/kotlin/sample/Service.kt"
            },
        )
    }

    @Test
    fun `reports malformed Kotlin without aborting other source extraction`() {
        val repository = tempDir.resolve("repo")
        write(repository.resolve("Broken.kt"), "package sample\nclass Broken(\n")
        write(repository.resolve("Healthy.kt"), "package sample\nclass Healthy\n")

        val result = KotlinExtractor().extract(repository, allowBuildTool = false)

        assertTrue(result.facts.any { it.qualifiedName == "sample.Healthy" })
        assertTrue(
            result.diagnostics.any {
                it.severity == "UNSUPPORTED" &&
                    it.code == "KOTLIN_SYNTAX_ERROR" &&
                    it.source?.path == "Broken.kt"
            },
        )
    }

    @Test
    fun `does not extract source files reached through a symlink`() {
        val repository = tempDir.resolve("repo")
        Files.createDirectories(repository)
        val outside = tempDir.resolve("Outside.kt")
        write(outside, "package leaked\nclass Secret\n")
        Files.createSymbolicLink(repository.resolve("Linked.kt"), outside)

        assertThrows<IllegalArgumentException> {
            KotlinExtractor().extract(repository, allowBuildTool = false)
        }
    }

    @Test
    fun `CLI rejects unknown options and writes JSON to the requested file`() {
        val repository = tempDir.resolve("repo")
        val output = tempDir.resolve("facts.json")
        write(repository.resolve("Sample.kt"), "package sample\nclass Sample\n")

        assertEquals(0, runCli(arrayOf("--help")))
        assertEquals(2, runCli(arrayOf("--repo", repository.toString(), "--unknown")))
        assertEquals(0, runCli(arrayOf("--repo", repository.toString(), "--out", output.toString())))

        val tree = jacksonObjectMapper().readTree(output.toFile())
        assertEquals("1.0", tree["schemaVersion"].asText())
        assertEquals("kotlin", tree["language"].asText())
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
