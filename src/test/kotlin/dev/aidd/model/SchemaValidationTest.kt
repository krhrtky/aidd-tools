package dev.aidd.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaValidationTest {
    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory
        .getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(Path.of("schema/model.schema.json").toUri())

    @Test
    fun `schema accepts an llm candidate with code fact evidence`() {
        val errors = schema.validate(
            mapper.readTree(
                model(
                    schemaVersion = "1.1",
                    node = """
                    {
                      "@id":"urn:aidd:schema:requirement:valid",
                      "@type":"Requirement",
                      "status":"candidate",
                      "basis":"derived",
                      "generatedBy":"llm",
                      "evidencedBy":["urn:aidd:fact:guard"]
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(emptySet(), errors)
    }

    @Test
    fun `schema accepts the existing version 1_1 formalize example`() {
        val errors = schema.validate(
            mapper.readTree(Path.of("examples/pure-function/model.jsonld").toFile()),
        )

        assertEquals(emptySet(), errors)
    }

    @Test
    fun `schema accepts a version 1_0 llm candidate with inline evidence`() {
        val errors = schema.validate(
            mapper.readTree(
                model(
                    schemaVersion = "1.0",
                    node = """
                    {
                      "@id":"urn:aidd:schema:requirement:inline",
                      "@type":"Requirement",
                      "status":"candidate",
                      "basis":"stated",
                      "generatedBy":"llm",
                      "evidence":[{
                        "path":"requirements.md",
                        "startLine":1,
                        "startColumn":1,
                        "endLine":1,
                        "endColumn":1,
                        "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                      }]
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(emptySet(), errors)
    }

    @Test
    fun `schema continues to accept version 1_0 accepted models`() {
        val errors = schema.validate(
            mapper.readTree(
                model(
                    schemaVersion = "1.0",
                    node = """
                    {
                      "@id":"urn:aidd:schema:state:accepted",
                      "@type":"State",
                      "status":"accepted",
                      "basis":"observed",
                      "generatedBy":"extractor",
                      "evidencedBy":["urn:aidd:fact:state"]
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(emptySet(), errors)
    }

    private fun model(schemaVersion: String, node: String): String =
        """
        {
          "@context":"https://aidd.dev/context/v1",
          "schemaVersion":"$schemaVersion",
          "specId":"schema-validation",
          "@graph":[$node]
        }
        """.trimIndent()
}
