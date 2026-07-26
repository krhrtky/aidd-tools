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
