package dev.aidd.cli

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormalizeCliTest {
    @Test
    fun `check writes deterministic provisional artifact set without approved bounds`() {
        val directory = Files.createTempDirectory("aidd-formalize-cli")
        val source = directory.resolve("requirements.md")
        source.writeText("状態Oneが存在する")
        val sourceHash = dev.aidd.model.Hashing.sha256(source)
        val model = directory.resolve("input.jsonld")
        model.writeText(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"simple",
              "@graph":[
                {
                  "@id":"urn:aidd:simple:state:one",
                  "@type":"State",
                  "label":"One",
                  "status":"accepted",
                  "basis":"stated",
                  "evidence":[{
                    "path":"requirements.md",
                    "startLine":1,
                    "startColumn":1,
                    "endLine":1,
                    "endColumn":10,
                    "sha256":"$sourceHash"
                  }]
                }
              ]
            }
            """.trimIndent(),
        )
        val output = directory.resolve("out")

        val exitCode = AiddCli().execute(
            listOf("formalize", "check", "--model", model.toString(), "--out", output.toString()),
        )

        assertEquals(3, exitCode)
        assertTrue(output.resolve("model.jsonld").exists())
        assertTrue(output.resolve("model.als").exists())
        assertTrue(output.resolve("bounds.json").exists())
        assertTrue(output.resolve("verification.json").exists())
        assertTrue(output.resolve("manifest.json").exists())
        assertTrue(output.resolve("spec.md").exists())
        val verification = ObjectMapper().readTree(output.resolve("verification.json").toFile())
        assertEquals("PROVISIONAL", verification.path("status").asText())

        val firstAlloy = output.resolve("model.als").readText()
        assertEquals(
            3,
            AiddCli().execute(
                listOf("formalize", "check", "--model", model.toString(), "--out", output.toString()),
            ),
        )
        assertEquals(firstAlloy, output.resolve("model.als").readText())
    }

    @Test
    fun `validate rejects accepted LLM claim without human decision`() {
        val directory = Files.createTempDirectory("aidd-formalize-invalid")
        val source = directory.resolve("requirements.md")
        source.writeText("x")
        val sourceHash = dev.aidd.model.Hashing.sha256(source)
        val model = directory.resolve("model.jsonld")
        model.writeText(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"invalid",
              "@graph":[
                {
                  "@id":"urn:aidd:invalid:req:x",
                  "@type":"Requirement",
                  "label":"x",
                  "status":"accepted",
                  "basis":"derived",
                  "generatedBy":"llm",
                  "evidence":[{
                    "path":"requirements.md",
                    "startLine":1,
                    "startColumn":1,
                    "endLine":1,
                    "endColumn":1,
                    "sha256":"$sourceHash"
                  }]
                }
              ]
            }
            """.trimIndent(),
        )

        val exitCode = AiddCli().execute(listOf("formalize", "validate", "--model", model.toString()))

        assertEquals(5, exitCode)
    }

    @Test
    fun `render refuses a symlink output without changing its target`() {
        val directory = Files.createTempDirectory("aidd-formalize-symlink")
        val model = directory.resolve("model.jsonld")
        model.writeText(
            """
            {"@context":"https://aidd.dev/context/v1","schemaVersion":"1.0","specId":"safe",
             "@graph":[{"@id":"urn:aidd:safe:state:one","@type":"State","label":"One",
             "status":"candidate","basis":"stated"}]}
            """.trimIndent(),
        )
        val victim = directory.resolve("victim.txt")
        victim.writeText("unchanged")
        val output = directory.resolve("spec.md")
        Files.createSymbolicLink(output, victim)

        val exitCode = AiddCli().execute(
            listOf("formalize", "render", "--model", model.toString(), "--out", output.toString()),
        )

        assertEquals(2, exitCode)
        assertEquals("unchanged", victim.readText())
    }
}
