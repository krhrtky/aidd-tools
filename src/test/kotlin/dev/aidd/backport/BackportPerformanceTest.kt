package dev.aidd.backport

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively

class BackportPerformanceTest {
    @Test
    fun `ten thousand facts validate and render within bounded resources`() {
        val directory = Files.createTempDirectory("aidd-backport-performance")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(codeFacts(10_000))
        val model = directory.resolve("model.jsonld")
        model.writeText(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"performance",
              "@graph":[]
            }
            """.trimIndent(),
        )
        val service = BackportService()
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        assertTrue(maxHeapBytes <= MAX_HEAP_BYTES, "maxHeapBytes=$maxHeapBytes")

        val validateStarted = System.nanoTime()
        val diagnostics = assertTimeoutPreemptively<List<BackportDiagnostic>>(
            Duration.ofSeconds(TIMEOUT_SECONDS),
        ) {
            service.validateFactsDetailed(facts, model)
        }
        val validateMillis = elapsedMillis(validateStarted)
        assertEquals(emptyList(), diagnostics)

        val renderStarted = System.nanoTime()
        val rendered = assertTimeoutPreemptively<String>(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            service.renderFacts(facts)
        }
        val renderMillis = elapsedMillis(renderStarted)
        assertEquals(10_000, rendered.lineSequence().count { it.startsWith("- **") })

        val report = Path.of("build/reports/backport-benchmark.json")
        Files.createDirectories(report.parent)
        report.writeText(
            """
            {
              "facts": 10000,
              "maxHeapBytes": $maxHeapBytes,
              "timeoutSecondsPerOperation": $TIMEOUT_SECONDS,
              "validateMillis": $validateMillis,
              "renderMillis": $renderMillis
            }
            """.trimIndent() + "\n",
        )
    }

    private fun codeFacts(count: Int): String = buildString {
        append(
            """
            {
              "schemaVersion":"1.0",
              "language":"kotlin",
              "extractor":{"name":"kotlin-compiler-psi","version":"0.1.0"},
              "repositorySha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "facts":[
            """.trimIndent(),
        )
        repeat(count) { index ->
            if (index > 0) append(',')
            append(
                """
                {
                  "id":"urn:aidd:performance:fact:$index",
                  "kind":"Guard",
                  "name":"guard-$index",
                  "qualifiedName":"Performance.guard$index",
                  "status":"accepted",
                  "basis":"observed",
                  "source":{
                    "path":"src/Performance.kt",
                    "startLine":1,
                    "startColumn":1,
                    "endLine":1,
                    "endColumn":1,
                    "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                  },
                  "details":{"index":$index}
                }
                """.trimIndent(),
            )
        }
        append(
            """
              ],
              "diagnostics":[]
            }
            """.trimIndent(),
        )
    }

    private fun elapsedMillis(started: Long): Long =
        Duration.ofNanos(System.nanoTime() - started).toMillis()

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val MAX_HEAP_BYTES = 512L * 1024L * 1024L
    }
}
