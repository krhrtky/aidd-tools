package dev.aidd.cli

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
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
    fun `explore checks candidates excludes rejected claims and writes provisional artifacts`() {
        val directory = Files.createTempDirectory("aidd-formalize-explore")
        val source = directory.resolve("requirements.md")
        source.writeText("候補状態は受理済み状態と異なる")
        val sourceHash = dev.aidd.model.Hashing.sha256(source)
        val model = directory.resolve("input.jsonld")
        model.writeText(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"explore",
              "@graph":[
                {
                  "@id":"urn:aidd:explore:state:accepted",
                  "@type":"State",
                  "label":"Accepted",
                  "status":"accepted",
                  "basis":"stated",
                  "evidence":[{
                    "path":"requirements.md","startLine":1,"startColumn":1,
                    "endLine":1,"endColumn":15,"sha256":"$sourceHash"
                  }]
                },
                {
                  "@id":"urn:aidd:explore:state:candidate",
                  "@type":"State",
                  "label":"Candidate",
                  "status":"candidate",
                  "basis":"derived",
                  "derivesFrom":["urn:aidd:explore:state:accepted"]
                },
                {
                  "@id":"urn:aidd:explore:invariant:distinct",
                  "@type":"Invariant",
                  "label":"Distinct",
                  "status":"candidate",
                  "basis":"derived",
                  "derivesFrom":["urn:aidd:explore:state:accepted"],
                  "expression":{"op":"neq","args":[
                    {"op":"ref","id":"urn:aidd:explore:state:accepted"},
                    {"op":"ref","id":"urn:aidd:explore:state:candidate"}
                  ]}
                },
                {
                  "@id":"urn:aidd:explore:state:rejected",
                  "@type":"State",
                  "label":"Rejected",
                  "status":"rejected",
                  "basis":"derived",
                  "derivesFrom":["urn:aidd:explore:state:accepted"]
                }
              ]
            }
            """.trimIndent(),
        )
        val output = directory.resolve("out")

        val exitCode = AiddCli().execute(
            listOf("formalize", "explore", "--model", model.toString(), "--out", output.toString()),
        )

        assertEquals(3, exitCode)
        assertTrue(output.resolve("candidate-spec.md").exists())
        assertTrue(!output.resolve("spec.md").exists())
        val alloy = output.resolve("model.als").readText()
        assertTrue(alloy.contains("State_Accepted"))
        assertTrue(alloy.contains("State_Candidate"))
        assertTrue(!alloy.contains("State_Rejected"))
        val verification = ObjectMapper().readTree(output.resolve("verification.json").toFile())
        assertEquals("PROVISIONAL", verification.path("status").asText())
        assertEquals(
            "NO_COUNTEREXAMPLE_WITHIN_SCOPE",
            verification.path("boundedOutcome").asText(),
        )
        val manifest = ObjectMapper().readTree(output.resolve("manifest.json").toFile())
        assertEquals("candidate-exploration", manifest.path("mode").asText())
        assertEquals(3, manifest.path("targetClaimIds").size())
        assertEquals(1, manifest.path("claimStatusCounts").path("accepted").asInt())
        assertEquals(2, manifest.path("claimStatusCounts").path("candidate").asInt())
        assertEquals(1, manifest.path("claimStatusCounts").path("rejected").asInt())
        assertTrue(manifest.path("outputSha256").has("candidate-spec.md"))
        val stableArtifacts = listOf("model.als", "verification.json", "candidate-spec.md", "manifest.json")
            .associateWith { output.resolve(it).readText() }

        assertEquals(
            3,
            AiddCli().execute(
                listOf("formalize", "explore", "--model", model.toString(), "--out", output.toString()),
            ),
        )
        stableArtifacts.forEach { (name, content) ->
            assertEquals(content, output.resolve(name).readText(), name)
        }
    }

    @Test
    fun `explore returns two when a candidate invariant has a counterexample`() {
        val directory = Files.createTempDirectory("aidd-formalize-counterexample")
        val source = directory.resolve("requirements.md")
        source.writeText("状態は自分自身と異なる")
        val sourceHash = dev.aidd.model.Hashing.sha256(source)
        val model = directory.resolve("input.jsonld")
        model.writeText(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"counterexample",
              "@graph":[
                {
                  "@id":"urn:aidd:counterexample:state:one",
                  "@type":"State","label":"One","status":"candidate","basis":"stated",
                  "evidence":[{"path":"requirements.md","startLine":1,"startColumn":1,
                    "endLine":1,"endColumn":12,"sha256":"$sourceHash"}]
                },
                {
                  "@id":"urn:aidd:counterexample:invariant:not-self",
                  "@type":"Invariant","label":"Not self","status":"candidate","basis":"derived",
                  "derivesFrom":["urn:aidd:counterexample:state:one"],
                  "expression":{"op":"neq","args":[
                    {"op":"ref","id":"urn:aidd:counterexample:state:one"},
                    {"op":"ref","id":"urn:aidd:counterexample:state:one"}
                  ]}
                }
              ]
            }
            """.trimIndent(),
        )

        val exitCode = AiddCli().execute(
            listOf(
                "formalize",
                "explore",
                "--model",
                model.toString(),
                "--out",
                directory.resolve("out").toString(),
            ),
        )

        assertEquals(2, exitCode)
    }

    @Test
    fun `explore fails closed with unsupported artifacts when a collection result is not defined`() {
        val directory = Files.createTempDirectory("aidd-formalize-unsupported")
        Files.copy(
            Path.of("examples/pure-function/requirements.md"),
            directory.resolve("requirements.md"),
        )
        val root = ObjectMapper().readTree(Path.of("examples/pure-function/model.jsonld").toFile())
        val result = root.path("@graph").first {
            it.path("@id").asText() == "urn:aidd:withdraw:result:new-balance"
        } as com.fasterxml.jackson.databind.node.ObjectNode
        result.set<com.fasterxml.jackson.databind.JsonNode>(
            "valueType",
            ObjectMapper().readTree("""{"kind":"list","elementType":{"kind":"int"}}"""),
        )
        val postcondition = root.path("@graph").first {
            it.path("@id").asText() == "urn:aidd:withdraw:postcondition:subtract"
        } as com.fasterxml.jackson.databind.node.ObjectNode
        postcondition.set<com.fasterxml.jackson.databind.JsonNode>(
            "expression",
            ObjectMapper().readTree(
                """
                {"op":"contains","args":[
                  {"op":"valueRef","id":"urn:aidd:withdraw:result:new-balance"},
                  {"op":"literal","value":0}
                ]}
                """.trimIndent(),
            ),
        )
        val model = directory.resolve("model.jsonld")
        model.writeText(ObjectMapper().writeValueAsString(root))
        val output = directory.resolve("out")

        val exitCode = AiddCli().execute(
            listOf("formalize", "explore", "--model", model.toString(), "--out", output.toString()),
        )

        assertEquals(4, exitCode)
        val verification = ObjectMapper().readTree(output.resolve("verification.json").toFile())
        assertEquals("UNSUPPORTED", verification.path("status").asText())
        assertTrue(output.resolve("model.als").exists())
        assertTrue(output.resolve("candidate-spec.md").exists())
        assertTrue(output.resolve("manifest.json").exists())
    }

    @Test
    fun `accept promotes only explicitly approved claims and records a hash-bound human decision`() {
        val directory = Files.createTempDirectory("aidd-formalize-accept")
        Files.copy(
            Path.of("examples/pure-function/requirements.md"),
            directory.resolve("requirements.md"),
        )
        val model = directory.resolve("model.jsonld")
        Files.copy(Path.of("examples/pure-function/model.jsonld"), model)
        val source = ObjectMapper().readTree(model.toFile())
        val claimIds = source.path("@graph").map { it.path("@id").asText() }.sorted()
        val decision = directory.resolve("decision.json")
        decision.writeText(
            ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                ObjectMapper().createObjectNode().apply {
                    put("schemaVersion", "1.0")
                    put("decisionId", "urn:aidd:withdraw:decision:accept-v1")
                    put("approvedBy", "contract-owner")
                    putArray("claims").also { array -> claimIds.forEach(array::add) }
                    putArray("assumptions")
                },
            ) + "\n",
        )
        val accepted = directory.resolve("accepted.jsonld")

        val exitCode = AiddCli().execute(
            listOf(
                "formalize",
                "accept",
                "--model",
                model.toString(),
                "--decision",
                decision.toString(),
                "--out",
                accepted.toString(),
            ),
        )

        assertEquals(0, exitCode)
        val approved = ObjectMapper().readTree(accepted.toFile())
        val approvedNodes = approved.path("@graph").associateBy { it.path("@id").asText() }
        claimIds.forEach { assertEquals("accepted", approvedNodes.getValue(it).path("status").asText()) }
        val humanDecision = approvedNodes.getValue("urn:aidd:withdraw:decision:accept-v1")
        assertEquals("HumanDecision", humanDecision.path("@type").asText())
        assertEquals("human", humanDecision.path("generatedBy").asText())
        assertEquals(claimIds, humanDecision.path("defines").map { it.asText() }.sorted())
        assertEquals(claimIds.toSet(), humanDecision.path("approvedClaimHashes").fieldNames().asSequence().toSet())
        assertTrue(
            humanDecision.path("approvedClaimHashes").properties().asSequence().all {
                it.value.asText().matches(Regex("[a-f0-9]{64}"))
            },
        )
        assertTrue(humanDecision.path("sourceModelSha256").asText().matches(Regex("[a-f0-9]{64}")))
        assertTrue(directory.resolve("accepted.acceptance.json").exists())
        assertEquals(0, AiddCli().execute(listOf("formalize", "validate", "--model", accepted.toString())))
    }

    @Test
    fun `accept rejects implicit assumptions and stale claim changes invalidate approval`() {
        val directory = Files.createTempDirectory("aidd-formalize-accept-assumption")
        val requirement = directory.resolve("requirements.md")
        requirement.writeText("gateway is atomic")
        val sourceHash = dev.aidd.model.Hashing.sha256(requirement)
        val model = directory.resolve("model.jsonld")
        model.writeText(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.1",
              "specId":"approval",
              "@graph":[
                {
                  "@id":"urn:aidd:approval:requirement:gateway",
                  "@type":"Requirement",
                  "label":"gateway is atomic",
                  "status":"candidate",
                  "basis":"stated",
                  "generatedBy":"llm",
                  "evidence":[{"path":"requirements.md","startLine":1,"startColumn":1,
                    "endLine":1,"endColumn":17,"sha256":"$sourceHash"}]
                },
                {
                  "@id":"urn:aidd:approval:assumption:gateway",
                  "@type":"Assumption",
                  "label":"gateway atomicity",
                  "status":"candidate",
                  "basis":"assumed",
                  "generatedBy":"llm",
                  "derivesFrom":["urn:aidd:approval:requirement:gateway"]
                }
              ]
            }
            """.trimIndent(),
        )
        val invalidDecision = directory.resolve("invalid-decision.json")
        invalidDecision.writeText(
            """
            {"schemaVersion":"1.0","decisionId":"urn:aidd:approval:decision:invalid",
             "approvedBy":"owner","claims":[
               "urn:aidd:approval:requirement:gateway",
               "urn:aidd:approval:assumption:gateway"
             ],"assumptions":[]}
            """.trimIndent(),
        )

        assertEquals(
            2,
            AiddCli().execute(
                listOf(
                    "formalize", "accept",
                    "--model", model.toString(),
                    "--decision", invalidDecision.toString(),
                    "--out", directory.resolve("invalid.jsonld").toString(),
                ),
            ),
        )
        val missingDecision = directory.resolve("missing-decision.json")
        missingDecision.writeText(
            """
            {"schemaVersion":"1.0","decisionId":"urn:aidd:approval:decision:missing",
             "approvedBy":"owner","claims":["urn:aidd:approval:missing"],"assumptions":[]}
            """.trimIndent(),
        )
        assertEquals(
            2,
            AiddCli().execute(
                listOf(
                    "formalize", "accept",
                    "--model", model.toString(),
                    "--decision", missingDecision.toString(),
                    "--out", directory.resolve("missing.jsonld").toString(),
                ),
            ),
        )

        val decision = directory.resolve("decision.json")
        decision.writeText(
            """
            {"schemaVersion":"1.0","decisionId":"urn:aidd:approval:decision:valid",
             "approvedBy":"owner","claims":["urn:aidd:approval:requirement:gateway"],
             "assumptions":["urn:aidd:approval:assumption:gateway"]}
            """.trimIndent(),
        )
        val accepted = directory.resolve("accepted.jsonld")
        assertEquals(
            0,
            AiddCli().execute(
                listOf(
                    "formalize", "accept",
                    "--model", model.toString(),
                    "--decision", decision.toString(),
                    "--out", accepted.toString(),
                ),
            ),
        )
        val changed = ObjectMapper().readTree(accepted.toFile())
        val requirementNode = changed.path("@graph").first {
            it.path("@id").asText() == "urn:aidd:approval:requirement:gateway"
        } as com.fasterxml.jackson.databind.node.ObjectNode
        requirementNode.put("label", "changed after approval")
        accepted.writeText(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(changed))

        assertEquals(5, AiddCli().execute(listOf("formalize", "validate", "--model", accepted.toString())))
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
