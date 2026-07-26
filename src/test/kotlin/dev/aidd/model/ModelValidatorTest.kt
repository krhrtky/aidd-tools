package dev.aidd.model

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelValidatorTest {
    @Test
    fun `valid model has no diagnostics`() {
        val directory = Files.createTempDirectory("aidd-model")
        val source = directory.resolve("requirements.md")
        source.writeText("残高は負にならない")
        val hash = Hashing.sha256(source)
        val model = directory.resolve("model.jsonld")
        model.writeText(
            """
            {
              "@context": "https://aidd.dev/context/v1",
              "schemaVersion": "1.0",
              "specId": "account",
              "@graph": [
                {
                  "@id": "urn:aidd:account:requirement:non-negative",
                  "@type": "Requirement",
                  "label": "残高は負にならない",
                  "status": "accepted",
                  "basis": "stated",
                  "evidence": [{
                    "path": "requirements.md",
                    "startLine": 1,
                    "startColumn": 1,
                    "endLine": 1,
                    "endColumn": 10,
                    "sha256": "$hash"
                  }]
                }
              ]
            }
            """.trimIndent(),
        )

        val result = ModelValidator().validate(model)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
        assertEquals("account", result.model?.specId)
    }

    @Test
    fun `dangling edge and accepted assumption require review`() {
        val directory = Files.createTempDirectory("aidd-invalid")
        val model = directory.resolve("model.jsonld")
        model.writeText(
            """
            {
              "@context": "https://aidd.dev/context/v1",
              "schemaVersion": "1.0",
              "specId": "payment",
              "@graph": [{
                "@id": "urn:aidd:payment:assumption:gateway",
                "@type": "Assumption",
                "label": "gateway is atomic",
                "status": "accepted",
                "basis": "assumed",
                "dependsOn": ["urn:aidd:payment:missing"]
              }]
            }
            """.trimIndent(),
        )

        val codes = ModelValidator().validate(model).diagnostics.map { it.code }.toSet()

        assertTrue("DANGLING_REFERENCE" in codes)
        assertTrue("ACCEPTED_ASSUMPTION_REQUIRES_DECISION" in codes)
    }

    @Test
    fun `accepted human decision authorizes explicit assumption`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"decision",
              "@graph":[
                {
                  "@id":"urn:aidd:decision:assumption:atomic",
                  "@type":"Assumption",
                  "status":"accepted",
                  "basis":"assumed",
                  "label":"atomic"
                },
                {
                  "@id":"urn:aidd:decision:human:approve-atomic",
                  "@type":"HumanDecision",
                  "status":"accepted",
                  "basis":"stated",
                  "label":"approve atomicity assumption",
                  "generatedBy":"human",
                  "evidence":[{
                    "path":"decision-record.md",
                    "startLine":1,
                    "startColumn":1,
                    "endLine":1,
                    "endColumn":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }],
                  "defines":["urn:aidd:decision:assumption:atomic"]
                }
              ]
            }
            """.trimIndent(),
        )

        val result = ModelValidator().validate(model)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
    }

    @Test
    fun `parser rejects unknown fields and scalar relations`() {
        val unknownField = runCatching {
            ModelParser().parse(
                """
                {"@context":"https://aidd.dev/context/v1","schemaVersion":"1.0","specId":"x",
                 "@graph":[{"@id":"urn:aidd:x:state:a","@type":"State","status":"candidate",
                 "basis":"stated","unexpected":true}]}
                """.trimIndent(),
            )
        }
        val scalarRelation = runCatching {
            ModelParser().parse(
                """
                {"@context":"https://aidd.dev/context/v1","schemaVersion":"1.0","specId":"x",
                 "@graph":[{"@id":"urn:aidd:x:transition:a","@type":"Transition","status":"candidate",
                 "basis":"stated","transitionsFrom":"urn:aidd:x:state:a"}]}
                """.trimIndent(),
            )
        }

        assertTrue(unknownField.isFailure)
        assertTrue(scalarRelation.isFailure)
    }

    @Test
    fun `typed expression rejects boolean ordering`() {
        val model = ModelParser().parse(
            """
            {"@context":"https://aidd.dev/context/v1","schemaVersion":"1.0","specId":"typed",
             "@graph":[{
               "@id":"urn:aidd:typed:constraint:x","@type":"Constraint","status":"candidate","basis":"derived",
               "expression":{"op":"lt","args":[
                 {"op":"literal","value":true},{"op":"literal","value":false}
               ]}
             }]}
            """.trimIndent(),
        )

        assertTrue(
            ModelValidator().validate(model).diagnostics.any { it.code == "EXPRESSION_TYPE_MISMATCH" },
        )
    }
}
