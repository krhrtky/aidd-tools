package dev.aidd.backport

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aidd.cli.AiddCli
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
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

    @Test
    fun `validate accepts an llm candidate only when it references an existing code fact`() {
        val directory = Files.createTempDirectory("aidd-candidate-validation")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(codeFacts())
        val candidate = directory.resolve("model.jsonld")
        candidate.writeText(
            model(
                "candidate",
                """
                {
                  "@id":"urn:aidd:candidate:requirement:limit",
                  "@type":"Requirement",
                  "label":"注文には上限がある",
                  "status":"candidate",
                  "basis":"derived",
                  "generatedBy":"llm",
                  "evidencedBy":["urn:aidd:fact:guard:limit"]
                }
                """.trimIndent(),
                schemaVersion = "1.1",
            ),
        )

        val diagnostics = BackportService().validateFactsDetailed(facts, candidate)

        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun `validate reports stable candidate evidence diagnostic when evidencedBy is missing`() {
        assertCandidateEvidenceDiagnostic(
            candidateFields = "",
            expectedCode = "MISSING_CANDIDATE_EVIDENCE",
            expectedMessagePart = "requires at least one evidencedBy CodeFact",
        )
    }

    @Test
    fun `validate reports stable candidate evidence diagnostic when evidencedBy is empty`() {
        assertCandidateEvidenceDiagnostic(
            candidateFields = ""","evidencedBy":[]""",
            expectedCode = "MISSING_CANDIDATE_EVIDENCE",
            expectedMessagePart = "requires at least one evidencedBy CodeFact",
        )
    }

    @Test
    fun `validate reports the unknown code fact id`() {
        assertCandidateEvidenceDiagnostic(
            candidateFields = ""","evidencedBy":["urn:aidd:fact:unknown"]""",
            expectedCode = "MISSING_CODE_FACT",
            expectedMessagePart = "urn:aidd:fact:unknown",
        )
    }

    @Test
    fun `validate rejects observed llm meaning and dangling fact evidence`() {
        val directory = Files.createTempDirectory("aidd-invalid-candidate")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(codeFacts())
        val candidate = directory.resolve("model.jsonld")
        candidate.writeText(
            model(
                "invalid-candidate",
                """
                {
                  "@id":"urn:aidd:candidate:requirement:purpose",
                  "@type":"Requirement",
                  "label":"業務目的",
                  "status":"candidate",
                  "basis":"observed",
                  "generatedBy":"llm",
                  "evidencedBy":["urn:aidd:fact:missing"]
                }
                """.trimIndent(),
                schemaVersion = "1.1",
            ),
        )

        val codes = BackportService().validateFactsDetailed(facts, candidate).map { it.code }.toSet()

        assertEquals(
            setOf("INVALID_LLM_CANDIDATE_BASIS", "MISSING_CODE_FACT"),
            codes,
        )
    }

    @Test
    fun `validate reports malformed fact fields and extractor diagnostics deterministically`() {
        val directory = Files.createTempDirectory("aidd-invalid-facts")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(
            """
            {
              "schemaVersion":"2.0",
              "language":"java",
              "extractor":{"name":"unknown","version":""},
              "repositorySha256":"invalid",
              "unknown":true,
              "facts":[
                {
                  "id":"invalid",
                  "kind":"Guard",
                  "name":"guard",
                  "qualifiedName":"guard",
                  "status":"candidate",
                  "basis":"derived",
                  "unknown":true,
                  "source":{
                    "path":"src/Invalid.kt",
                    "startLine":0,
                    "startColumn":0,
                    "endLine":0,
                    "endColumn":0,
                    "sha256":"invalid",
                    "unknown":true
                  },
                  "details":{}
                },
                {
                  "id":"invalid",
                  "kind":"Guard",
                  "name":"duplicate",
                  "qualifiedName":"duplicate",
                  "status":"accepted",
                  "basis":"observed",
                  "source":{
                    "path":"src/Invalid.kt",
                    "startLine":1,
                    "startColumn":1,
                    "endLine":1,
                    "endColumn":1,
                    "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                  },
                  "details":{}
                }
              ],
              "diagnostics":[{
                "code":"SEMANTIC_CLASSPATH_REQUIRED",
                "severity":"UNSUPPORTED",
                "message":"semantic classpath is required"
              }]
            }
            """.trimIndent(),
        )
        val model = directory.resolve("model.jsonld")
        model.writeText(model("invalid-facts", ""))

        val diagnostics = BackportService().validateFactsDetailed(facts, model)

        assertEquals(13, diagnostics.size)
        assertTrue(diagnostics.any { it.code == "SEMANTIC_CLASSPATH_REQUIRED" })
        assertTrue(diagnostics.any { it.message == "CodeFacts: unknown field unknown" })
        assertTrue(diagnostics.any { it.message == "CodeFacts: duplicate fact id invalid" })
        assertTrue(diagnostics.any { it.message == "invalid: invalid source evidence" })
        assertEquals(
            2,
            AiddCli().execute(
                listOf(
                    "backport", "validate",
                    "--facts", facts.toString(),
                    "--model", model.toString(),
                ),
            ),
        )
    }

    @Test
    fun `backport validate returns unsupported for extractor diagnostics`() {
        val directory = Files.createTempDirectory("aidd-unsupported-facts")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(
            codeFacts().replace(
                "\"diagnostics\":[]",
                """
                "diagnostics":[{
                  "code":"SEMANTIC_CLASSPATH_REQUIRED",
                  "severity":"UNSUPPORTED",
                  "message":"semantic classpath is required"
                }]
                """.trimIndent(),
            ),
        )
        val model = directory.resolve("model.jsonld")
        model.writeText(model("unsupported", ""))
        val exitCode = AiddCli().execute(
            listOf(
                "backport", "validate",
                "--facts", facts.toString(),
                "--model", model.toString(),
            ),
        )

        assertEquals(3, exitCode)
        val details = BackportService().validateFactsDetailed(facts, model)
        assertEquals("SEMANTIC_CLASSPATH_REQUIRED", details.single().code)
        assertEquals(BackportDiagnosticSeverity.UNSUPPORTED, details.single().severity)
    }

    @Test
    fun `backport explore reuses formal exploration and writes business artifact names`() {
        val directory = Files.createTempDirectory("aidd-backport-explore")
        val output = directory.resolve("out")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(codeFacts())

        val exitCode = AiddCli().execute(
            listOf(
                "backport",
                "explore",
                "--model",
                Path.of("examples/pure-function/model.jsonld").toString(),
                "--bounds",
                Path.of("examples/pure-function/bounds.json").toString(),
                "--facts",
                facts.toString(),
                "--out",
                output.toString(),
            ),
        )

        assertEquals(0, exitCode)
        assertTrue(output.resolve("candidate.als").exists())
        assertTrue(output.resolve("exploration.json").exists())
        assertTrue(output.resolve("candidate-prose.md").exists())
        val exploration = ObjectMapper().readTree(output.resolve("exploration.json").toFile())
        assertEquals("PROVISIONAL", exploration.path("status").asText())
        assertTrue(exploration.hasNonNull("boundedOutcome"))
        val manifest = ObjectMapper().readTree(output.resolve("manifest.json").toFile())
        assertEquals("candidate-exploration", manifest.path("mode").asText())
        assertEquals("kotlin-compiler-psi", manifest.path("extractor").path("name").asText())
        assertEquals(dev.aidd.model.Hashing.sha256(facts), manifest.path("inputCodeFactsSha256").asText())
        assertTrue(manifest.path("outputSha256").has("candidate.als"))
    }

    @Test
    fun `backport render supports candidate business view without changing facts render`() {
        val directory = Files.createTempDirectory("aidd-backport-render")
        val output = directory.resolve("candidate-prose.md")

        val exitCode = AiddCli().execute(
            listOf(
                "backport",
                "render",
                "--model",
                Path.of("examples/pure-function/model.jsonld").toString(),
                "--view",
                "candidate-business",
                "--out",
                output.toString(),
            ),
        )

        assertEquals(0, exitCode)
        assertTrue(output.exists())
        assertTrue(output.toFile().readText().contains("# 業務仕様候補"))
    }

    @Test
    fun `backport cli keeps legacy render diff and usage exit contracts`() {
        val directory = Files.createTempDirectory("aidd-backport-contracts")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(codeFacts())
        val rendered = directory.resolve("as-built.md")
        val model = directory.resolve("model.jsonld")
        model.writeText(model("contracts", ""))

        assertEquals(
            0,
            AiddCli().execute(
                listOf("backport", "render", "--facts", facts.toString(), "--out", rendered.toString()),
            ),
        )
        assertTrue(rendered.readText().startsWith("# As-built Specification"))
        assertEquals(
            0,
            AiddCli().execute(
                listOf(
                    "backport", "render",
                    "--model", model.toString(),
                    "--view", "accepted",
                    "--out", directory.resolve("accepted.md").toString(),
                ),
            ),
        )
        assertEquals(
            2,
            AiddCli().execute(
                listOf(
                    "backport", "render",
                    "--facts", facts.toString(),
                    "--model", model.toString(),
                    "--view", "candidate-business",
                    "--out", directory.resolve("invalid.md").toString(),
                ),
            ),
        )
        assertEquals(
            2,
            AiddCli().execute(
                listOf(
                    "backport", "render",
                    "--model", model.toString(),
                    "--view", "unknown",
                    "--out", directory.resolve("unknown.md").toString(),
                ),
            ),
        )
        assertEquals(
            0,
            AiddCli().execute(
                listOf(
                    "backport", "diff",
                    "--model", model.toString(),
                    "--against", model.toString(),
                    "--out", directory.resolve("diff.json").toString(),
                ),
            ),
        )
        val intended = directory.resolve("intended.jsonld")
        intended.writeText(
            model(
                "contracts",
                """
                {
                  "@id":"urn:aidd:contracts:requirement:missing",
                  "@type":"Requirement",
                  "status":"accepted",
                  "basis":"stated"
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            5,
            AiddCli().execute(
                listOf(
                    "backport", "diff",
                    "--model", model.toString(),
                    "--against", intended.toString(),
                    "--out", directory.resolve("different.json").toString(),
                ),
            ),
        )
        assertEquals(2, AiddCli().execute(emptyList()))
        assertEquals(2, AiddCli().execute(listOf("formalize", "unknown")))
        assertEquals(2, AiddCli().execute(listOf("backport", "unknown")))
        assertEquals(
            4,
            AiddCli().execute(
                listOf(
                    "backport", "extract",
                    "--repo", directory.toString(),
                    "--language", "kotlin",
                    "--contracts", "contract.json",
                    "--out", directory.resolve("facts").toString(),
                ),
            ),
        )
        assertEquals(
            4,
            AiddCli().execute(
                listOf(
                    "formalize", "generate",
                    "--model", model.toString(),
                    "--contract", "urn:aidd:contracts:contract:missing",
                    "--language", "kotlin",
                    "--out", directory.resolve("generated.kt").toString(),
                ),
            ),
        )
        assertEquals(
            4,
            AiddCli().execute(
                listOf(
                    "formalize", "generate",
                    "--model", model.toString(),
                    "--contract", "urn:aidd:contracts:contract:missing",
                    "--language", "typescript",
                    "--out", directory.resolve("generated.ts").toString(),
                ),
            ),
        )
        assertEquals(
            2,
            AiddCli().execute(
                listOf(
                    "formalize", "check",
                    "--model", model.toString(),
                    "--bounds", directory.resolve("missing-bounds.json").toString(),
                    "--out", directory.resolve("invalid-bounds").toString(),
                ),
            ),
        )
    }

    @Test
    fun `backport refine fails closed when no observed operation exists`() {
        val directory = Files.createTempDirectory("aidd-backport-refine")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(codeFacts())
        val model = directory.resolve("model.jsonld")
        model.writeText(model("refine", ""))
        val output = directory.resolve("out")

        val exitCode = AiddCli().execute(
            listOf(
                "backport", "refine",
                "--facts", facts.toString(),
                "--model", model.toString(),
                "--contract", "urn:aidd:refine:contract:missing",
                "--operation", "missing",
                "--out", output.toString(),
            ),
        )

        assertEquals(4, exitCode)
        assertTrue(output.resolve("refinement.als").readText().startsWith("// UNSUPPORTED:"))
        assertEquals(
            "UNSUPPORTED",
            ObjectMapper().readTree(output.resolve("refinement.json").toFile()).path("status").asText(),
        )
    }

    @Test
    fun `backport check excludes candidate meaning and retains the accepted alloy alias`() {
        val directory = Files.createTempDirectory("aidd-backport-check")
        val evidence = directory.resolve("source.md")
        evidence.writeText("accepted")
        val hash = dev.aidd.model.Hashing.sha256(evidence)
        val accepted = directory.resolve("accepted.jsonld")
        accepted.writeText(
            model(
                "separation",
                """
                {
                  "@id":"urn:aidd:separation:state:accepted",
                  "@type":"State",
                  "label":"Accepted",
                  "status":"accepted",
                  "basis":"stated",
                  "evidence":[{
                    "path":"source.md","startLine":1,"startColumn":1,
                    "endLine":1,"endColumn":8,"sha256":"$hash"
                  }]
                }
                """.trimIndent(),
            ),
        )
        val mixed = directory.resolve("mixed.jsonld")
        mixed.writeText(
            model(
                "separation",
                """
                {
                  "@id":"urn:aidd:separation:state:accepted",
                  "@type":"State",
                  "label":"Accepted",
                  "status":"accepted",
                  "basis":"stated",
                  "evidence":[{
                    "path":"source.md","startLine":1,"startColumn":1,
                    "endLine":1,"endColumn":8,"sha256":"$hash"
                  }]
                },
                {
                  "@id":"urn:aidd:separation:state:candidate",
                  "@type":"State",
                  "label":"Candidate",
                  "status":"candidate",
                  "basis":"derived",
                  "derivesFrom":["urn:aidd:separation:state:accepted"]
                }
                """.trimIndent(),
            ),
        )
        val acceptedOutput = directory.resolve("accepted-out")
        val mixedOutput = directory.resolve("mixed-out")

        assertEquals(
            3,
            AiddCli().execute(
                listOf(
                    "backport", "check", "--model", accepted.toString(),
                    "--out", acceptedOutput.toString(),
                ),
            ),
        )
        assertEquals(
            3,
            AiddCli().execute(
                listOf(
                    "backport", "check", "--model", mixed.toString(),
                    "--out", mixedOutput.toString(),
                ),
            ),
        )

        assertEquals(
            acceptedOutput.resolve("accepted.als").readText(),
            mixedOutput.resolve("accepted.als").readText(),
        )
        assertEquals(
            mixedOutput.resolve("model.als").readText(),
            mixedOutput.resolve("accepted.als").readText(),
        )
    }

    @Test
    fun `backport explore reports unsupported without approximating a collection contract`() {
        val directory = Files.createTempDirectory("aidd-backport-unsupported")
        Files.copy(
            Path.of("examples/pure-function/requirements.md"),
            directory.resolve("requirements.md"),
        )
        val mapper = ObjectMapper()
        val root = mapper.readTree(Path.of("examples/pure-function/model.jsonld").toFile())
        val result = root.path("@graph").first {
            it.path("@id").asText() == "urn:aidd:withdraw:result:new-balance"
        } as com.fasterxml.jackson.databind.node.ObjectNode
        result.set<com.fasterxml.jackson.databind.JsonNode>(
            "valueType",
            mapper.readTree("""{"kind":"list","elementType":{"kind":"int"}}"""),
        )
        val postcondition = root.path("@graph").first {
            it.path("@id").asText() == "urn:aidd:withdraw:postcondition:subtract"
        } as com.fasterxml.jackson.databind.node.ObjectNode
        postcondition.set<com.fasterxml.jackson.databind.JsonNode>(
            "expression",
            mapper.readTree(
                """
                {"op":"contains","args":[
                  {"op":"valueRef","id":"urn:aidd:withdraw:result:new-balance"},
                  {"op":"literal","value":0}
                ]}
                """.trimIndent(),
            ),
        )
        val model = directory.resolve("model.jsonld")
        model.writeText(mapper.writeValueAsString(root))
        val output = directory.resolve("out")

        val exitCode = AiddCli().execute(
            listOf("backport", "explore", "--model", model.toString(), "--out", output.toString()),
        )

        assertEquals(3, exitCode)
        val exploration = mapper.readTree(output.resolve("exploration.json").toFile())
        assertEquals("UNSUPPORTED", exploration.path("status").asText())
        val manifest = mapper.readTree(output.resolve("manifest.json").toFile())
        assertEquals("UNSUPPORTED", manifest.path("diagnostics").single().path("code").asText())
        assertTrue(output.resolve("candidate.als").readText().startsWith("// Candidate model could not be compiled"))
    }

    private fun codeFacts(): String =
        """
        {
          "schemaVersion":"1.0",
          "language":"kotlin",
          "extractor":{"name":"kotlin-compiler-psi","version":"0.1.0"},
          "repositorySha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "facts":[{
            "id":"urn:aidd:fact:guard:limit",
            "kind":"Guard",
            "name":"limit",
            "qualifiedName":"Order.limit",
            "status":"accepted",
            "basis":"observed",
            "source":{
              "path":"src/Order.kt",
              "startLine":1,
              "startColumn":1,
              "endLine":1,
              "endColumn":10,
              "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            },
            "details":{}
          }],
          "diagnostics":[]
        }
        """.trimIndent()

    private fun assertCandidateEvidenceDiagnostic(
        candidateFields: String,
        expectedCode: String,
        expectedMessagePart: String,
    ) {
        val directory = Files.createTempDirectory("aidd-candidate-evidence")
        val facts = directory.resolve("code-facts.json")
        facts.writeText(codeFacts())
        val candidate = directory.resolve("model.jsonld")
        candidate.writeText(
            model(
                "candidate-evidence",
                """
                {
                  "@id":"urn:aidd:candidate:requirement:evidence",
                  "@type":"Requirement",
                  "label":"candidate evidence",
                  "status":"candidate",
                  "basis":"derived",
                  "generatedBy":"llm"
                  $candidateFields
                }
                """.trimIndent(),
                schemaVersion = "1.1",
            ),
        )

        val diagnostics = BackportService().validateFactsDetailed(facts, candidate)

        assertEquals(listOf(expectedCode), diagnostics.map(BackportDiagnostic::code))
        assertTrue(diagnostics.single().message.contains(expectedMessagePart), diagnostics.single().message)
    }

    private fun model(specId: String, graph: String, schemaVersion: String = "1.0"): String =
        """
        {
          "@context":"https://aidd.dev/context/v1",
          "schemaVersion":"$schemaVersion",
          "specId":"$specId",
          "@graph":[$graph]
        }
        """.trimIndent()
}
