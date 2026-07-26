package dev.aidd.backport

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackportServiceTest {
    @Test
    fun `diff distinguishes matched missing extra contradictions and evidence gaps`() {
        val directory = Files.createTempDirectory("aidd-diff")
        val intended = directory.resolve("intended.jsonld")
        val observed = directory.resolve("observed.jsonld")
        intended.writeText(
            model(
                "intent",
                """
                {"@id":"urn:aidd:req:one","@type":"Requirement","label":"one","status":"accepted","basis":"stated"},
                {"@id":"urn:aidd:req:missing","@type":"Requirement","label":"missing","status":"accepted","basis":"stated"}
                """.trimIndent(),
            ),
        )
        observed.writeText(
            model(
                "observed",
                """
                {"@id":"urn:aidd:req:one","@type":"Requirement","label":"one","status":"accepted","basis":"observed"},
                {"@id":"urn:aidd:req:extra","@type":"Requirement","label":"extra","status":"accepted","basis":"observed"},
                {
                  "@id":"urn:aidd:req:conflict",
                  "@type":"Requirement",
                  "label":"conflict",
                  "status":"accepted",
                  "basis":"observed",
                  "contradicts":["urn:aidd:req:one"]
                }
                """.trimIndent(),
            ),
        )

        val result = BackportService().diff(observed, intended)

        assertEquals(listOf("urn:aidd:req:one"), result.matched)
        assertEquals(listOf("urn:aidd:req:missing"), result.missing)
        assertTrue("urn:aidd:req:extra" in result.extra)
        assertEquals(listOf("urn:aidd:req:conflict -> urn:aidd:req:one"), result.contradictions)
        assertTrue(result.evidenceMissing.isNotEmpty())
    }

    private fun model(specId: String, graph: String): String =
        """
        {
          "@context":"https://aidd.dev/context/v1",
          "schemaVersion":"1.0",
          "specId":"$specId",
          "@graph":[$graph]
        }
        """.trimIndent()
}

