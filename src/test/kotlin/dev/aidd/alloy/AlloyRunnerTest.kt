package dev.aidd.alloy

import dev.aidd.model.ModelParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AlloyRunnerTest {
    @Test
    fun `legacy bounds default max list length to three`() {
        val path = Files.createTempFile("aidd-bounds", ".json")
        Files.writeString(
            path,
            """{"globalScope":3,"intBitwidth":4,"maxTraceSteps":10,"approved":false}""",
        )

        assertEquals(3, Bounds.read(path).maxListLength)
    }

    @Test
    fun `violated invariant returns counterexample`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"inconsistent",
              "@graph":[
                {"@id":"urn:aidd:inconsistent:state:one","@type":"State","label":"One","status":"accepted","basis":"stated"},
                {
                  "@id":"urn:aidd:inconsistent:constraint:not-self",
                  "@type":"Invariant",
                  "label":"not self",
                  "status":"accepted",
                  "basis":"stated",
                  "expression":{"op":"neq","args":[
                    {"op":"ref","id":"urn:aidd:inconsistent:state:one"},
                    {"op":"ref","id":"urn:aidd:inconsistent:state:one"}
                  ]}
                }
              ]
            }
            """.trimIndent(),
        )
        val bounds = Bounds(3, 4, 10, true, "test-reviewer", "urn:aidd:test:decision:bounds")
        val alloy = AlloyCompiler().compile(model, bounds)

        val result = AlloyRunner().run(alloy, bounds, Files.createTempDirectory("aidd-alloy"))

        assertEquals(VerificationStatus.COUNTEREXAMPLE, result.status)
    }

    @Test
    fun `approved inconsistent base constraints return no instance within scope`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"inconsistent",
              "@graph":[
                {"@id":"urn:aidd:inconsistent:state:one","@type":"State","label":"One","status":"accepted","basis":"stated"},
                {
                  "@id":"urn:aidd:inconsistent:constraint:not-self",
                  "@type":"Constraint",
                  "label":"not self",
                  "status":"accepted",
                  "basis":"stated",
                  "expression":{"op":"neq","args":[
                    {"op":"ref","id":"urn:aidd:inconsistent:state:one"},
                    {"op":"ref","id":"urn:aidd:inconsistent:state:one"}
                  ]}
                }
              ]
            }
            """.trimIndent(),
        )
        val bounds = Bounds(3, 4, 10, true, "test-reviewer", "urn:aidd:test:decision:bounds")
        val result = AlloyRunner().run(
            AlloyCompiler().compile(model, bounds),
            bounds,
            Files.createTempDirectory("aidd-alloy"),
        )

        assertEquals(VerificationStatus.NO_INSTANCE_WITHIN_SCOPE, result.status)
    }

    @Test
    fun `satisfiability without an assertion is never reported as no counterexample`() {
        val model = ModelParser().parse(
            """
            {"@context":"https://aidd.dev/context/v1","schemaVersion":"1.0","specId":"sat",
             "@graph":[{"@id":"urn:aidd:sat:state:one","@type":"State","label":"One",
             "status":"accepted","basis":"stated"}]}
            """.trimIndent(),
        )
        val bounds = Bounds(3, 4, 10, true, "test-reviewer", "urn:aidd:test:decision:bounds")

        val result = AlloyRunner().run(
            AlloyCompiler().compile(model, bounds),
            bounds,
            Files.createTempDirectory("aidd-alloy"),
        )

        assertEquals(VerificationStatus.PROVISIONAL, result.status)
        assertEquals(null, result.boundedOutcome)
    }

    @Test
    fun `candidate exploration is provisional and retains bounded satisfiability outcome`() {
        val model = ModelParser().parse(
            """
            {"@context":"https://aidd.dev/context/v1","schemaVersion":"1.0","specId":"sat",
             "@graph":[{"@id":"urn:aidd:sat:state:one","@type":"State","label":"One",
             "status":"candidate","basis":"stated"}]}
            """.trimIndent(),
        )
        val bounds = Bounds(3, 4, 10, true, "test-reviewer", "urn:aidd:test:decision:bounds")

        val result = AlloyRunner().run(
            AlloyCompiler().compile(model, bounds, ClaimSelection.ACCEPTED_AND_CANDIDATE),
            bounds,
            Files.createTempDirectory("aidd-alloy"),
            forceProvisional = true,
        )

        assertEquals(VerificationStatus.PROVISIONAL, result.status)
        assertEquals(VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE, result.boundedOutcome)
    }

    @Test
    fun `withdraw contract is satisfiable deterministic disjoint and total within scope`() {
        val model = ModelParser().parse(Path.of("examples/pure-function/model.jsonld"))
        val bounds = Bounds.defaultExploration()

        val result = AlloyRunner().run(
            AlloyCompiler().compile(model, bounds, ClaimSelection.ACCEPTED_AND_CANDIDATE),
            bounds,
            Files.createTempDirectory("aidd-alloy-contract"),
            forceProvisional = true,
        )

        assertEquals(VerificationStatus.PROVISIONAL, result.status)
        assertEquals(VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE, result.boundedOutcome)
        assertEquals(true, result.commands.first { it.label.endsWith("_PreSatisfiable") }.satisfiable)
        assertEquals(
            false,
            result.commands.first { it.label.endsWith("_ValidResultExactlyOne") }.satisfiable,
        )
        assertEquals(
            false,
            result.commands.first { it.label.endsWith("_TotalInvalidHasExactlyOneError") }.satisfiable,
        )
    }

    @Test
    fun `contract exploration detects unsatisfiable precondition`() {
        val root = pureFunctionFixture()
        root.graphNode("urn:aidd:withdraw:precondition:success").set<com.fasterxml.jackson.databind.JsonNode>(
            "expression",
            com.fasterxml.jackson.databind.ObjectMapper().readTree("""{"op":"literal","value":false}"""),
        )

        val result = explore(root)

        assertEquals(VerificationStatus.NO_INSTANCE_WITHIN_SCOPE, result.boundedOutcome)
        assertEquals(false, result.commands.first { it.label.endsWith("_PreSatisfiable") }.satisfiable)
    }

    @Test
    fun `contract exploration detects non unique result`() {
        val root = pureFunctionFixture()
        root.graphNode("urn:aidd:withdraw:postcondition:subtract").set<com.fasterxml.jackson.databind.JsonNode>(
            "expression",
            com.fasterxml.jackson.databind.ObjectMapper().readTree("""{"op":"literal","value":true}"""),
        )

        val result = explore(root)

        assertEquals(VerificationStatus.COUNTEREXAMPLE, result.boundedOutcome)
        assertEquals(true, result.commands.first { it.label.endsWith("_ValidResultExactlyOne") }.satisfiable)
    }

    @Test
    fun `contract exploration detects overlapping errors`() {
        val root = pureFunctionFixture()
        val duplicated = root.graphNode("urn:aidd:withdraw:error:invalid-balance").path("expression").deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        root.graphNode("urn:aidd:withdraw:error:invalid-amount").set<com.fasterxml.jackson.databind.JsonNode>(
            "expression",
            duplicated,
        )

        val result = explore(root)

        assertEquals(VerificationStatus.COUNTEREXAMPLE, result.boundedOutcome)
        assertEquals(true, result.commands.first { it.label.endsWith("_ErrorsDisjoint") }.satisfiable)
    }

    @Test
    fun `distinct errors may share a human readable label`() {
        val root = pureFunctionFixture()
        root.graphNode("urn:aidd:withdraw:error:invalid-balance").put("label", "Invalid input")
        root.graphNode("urn:aidd:withdraw:error:invalid-amount").put("label", "Invalid input")

        val result = explore(root)

        assertEquals(VerificationStatus.PROVISIONAL, result.status, result.diagnostics.joinToString())
        assertEquals(VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE, result.boundedOutcome)
    }

    @Test
    fun `total contract exploration detects uncovered invalid input`() {
        val root = pureFunctionFixture()
        val operation = root.graphNode("urn:aidd:withdraw:operation:withdraw")
        operation.withArray("mayFailWith").removeAll().apply {
            add("urn:aidd:withdraw:error:invalid-balance")
            add("urn:aidd:withdraw:error:invalid-amount")
        }
        val contract = root.graphNode("urn:aidd:withdraw:contract:withdraw")
        val constraints = contract.withArray("constrains")
        constraints.removeAll()
        listOf(
            "urn:aidd:withdraw:precondition:success",
            "urn:aidd:withdraw:postcondition:subtract",
            "urn:aidd:withdraw:error:invalid-balance",
            "urn:aidd:withdraw:error:invalid-amount",
        ).forEach(constraints::add)
        val graph = root.withArray("@graph")
        graph.remove(
            graph.indexOfFirst {
                it.path("@id").asText() == "urn:aidd:withdraw:error:insufficient-funds"
            },
        )

        val result = explore(root)

        assertEquals(VerificationStatus.COUNTEREXAMPLE, result.boundedOutcome)
        assertEquals(
            true,
            result.commands.first { it.label.endsWith("_TotalInvalidHasExactlyOneError") }.satisfiable,
        )
    }

    @Test
    fun `list contract uses configured finite sequence scope`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1","schemaVersion":"1.1","specId":"append",
              "@graph":[
                {"@id":"urn:aidd:append:p:xs","@type":"Parameter","label":"xs","status":"candidate",
                 "basis":"stated","valueType":{"kind":"list","elementType":{"kind":"int"}}},
                {"@id":"urn:aidd:append:r:ys","@type":"Result","label":"ys","status":"candidate",
                 "basis":"stated","valueType":{"kind":"list","elementType":{"kind":"int"}}},
                {"@id":"urn:aidd:append:op","@type":"Operation","label":"append","status":"candidate",
                 "basis":"stated","accepts":["urn:aidd:append:p:xs"],"returns":["urn:aidd:append:r:ys"]},
                {"@id":"urn:aidd:append:pre","@type":"Precondition","label":"room","status":"candidate",
                 "basis":"stated","expression":{"op":"lt","args":[
                   {"op":"size","args":[{"op":"valueRef","id":"urn:aidd:append:p:xs"}]},
                   {"op":"literal","value":3}
                 ]}},
                {"@id":"urn:aidd:append:post","@type":"Postcondition","label":"appended","status":"candidate",
                 "basis":"stated","expression":{"op":"eq","args":[
                   {"op":"valueRef","id":"urn:aidd:append:r:ys"},
                   {"op":"append","args":[
                     {"op":"valueRef","id":"urn:aidd:append:p:xs"},{"op":"literal","value":1}
                   ]}
                 ]}},
                {"@id":"urn:aidd:append:contract","@type":"Contract","label":"append contract",
                 "status":"candidate","basis":"stated","total":false,
                 "defines":["urn:aidd:append:op"],"constrains":["urn:aidd:append:pre","urn:aidd:append:post"]}
              ]
            }
            """.trimIndent(),
        )
        val bounds = Bounds.defaultExploration()
        val alloy = AlloyCompiler().compile(model, bounds, ClaimSelection.ACCEPTED_AND_CANDIDATE)

        val result = AlloyRunner().run(
            alloy,
            bounds,
            Files.createTempDirectory("aidd-alloy-list-contract"),
            forceProvisional = true,
        )

        assertEquals(true, alloy.contains("but 4 Int, 3 seq"))
        assertEquals(VerificationStatus.PROVISIONAL, result.status, "${result.diagnostics}\n$alloy")
        assertEquals(VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE, result.boundedOutcome)
    }

    @Test
    fun `collection result may be defined inside a conjunctive postcondition`() {
        val root = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Path.of("examples/pure-function/model.jsonld").toFile())
        val result = root.path("@graph").first {
            it.path("@id").asText() == "urn:aidd:withdraw:result:new-balance"
        } as com.fasterxml.jackson.databind.node.ObjectNode
        result.set<com.fasterxml.jackson.databind.JsonNode>(
            "valueType",
            com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("""{"kind":"list","elementType":{"kind":"int"}}"""),
        )
        val postcondition = root.path("@graph").first {
            it.path("@id").asText() == "urn:aidd:withdraw:postcondition:subtract"
        } as com.fasterxml.jackson.databind.node.ObjectNode
        postcondition.set<com.fasterxml.jackson.databind.JsonNode>(
            "expression",
            com.fasterxml.jackson.databind.ObjectMapper().readTree(
                """
                {"op":"and","args":[
                  {"op":"eq","args":[
                    {"op":"valueRef","id":"urn:aidd:withdraw:result:new-balance"},
                    {"op":"listLiteral","valueType":{"kind":"int"},"args":[]}
                  ]},
                  {"op":"eq","args":[
                    {"op":"size","args":[
                      {"op":"valueRef","id":"urn:aidd:withdraw:result:new-balance"}
                    ]},
                    {"op":"literal","value":0}
                  ]}
                ]}
                """.trimIndent(),
            ),
        )
        val model = ModelParser().parse(root.toString())
        val bounds = Bounds.defaultExploration()

        val verification = AlloyRunner().run(
            AlloyCompiler().compile(model, bounds, ClaimSelection.ACCEPTED_AND_CANDIDATE),
            bounds,
            Files.createTempDirectory("aidd-alloy-conjunctive-list"),
            forceProvisional = true,
        )

        assertEquals(VerificationStatus.PROVISIONAL, verification.status, verification.diagnostics.joinToString())
        assertEquals(VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE, verification.boundedOutcome)
    }

    @Test
    fun `string enum and set contract expressions execute in Alloy`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1","schemaVersion":"1.1","specId":"typed-values",
              "@graph":[
                {"@id":"urn:aidd:typed:type:color","@type":"Type","label":"Color","status":"candidate",
                 "basis":"stated","members":["RED","BLUE"]},
                {"@id":"urn:aidd:typed:p:name","@type":"Parameter","label":"name","status":"candidate",
                 "basis":"stated","valueType":{"kind":"string"}},
                {"@id":"urn:aidd:typed:p:color","@type":"Parameter","label":"color","status":"candidate",
                 "basis":"stated","valueType":{"kind":"enum","typeId":"urn:aidd:typed:type:color"}},
                {"@id":"urn:aidd:typed:p:tags","@type":"Parameter","label":"tags","status":"candidate",
                 "basis":"stated","valueType":{"kind":"set","elementType":{"kind":"int"}}},
                {"@id":"urn:aidd:typed:r:accepted","@type":"Result","label":"accepted","status":"candidate",
                 "basis":"stated","valueType":{"kind":"bool"}},
                {"@id":"urn:aidd:typed:op","@type":"Operation","label":"accept","status":"candidate",
                 "basis":"stated","accepts":["urn:aidd:typed:p:name","urn:aidd:typed:p:color","urn:aidd:typed:p:tags"],
                 "returns":["urn:aidd:typed:r:accepted"]},
                {"@id":"urn:aidd:typed:pre","@type":"Precondition","label":"typed input","status":"candidate",
                 "basis":"stated","expression":{"op":"and","args":[
                   {"op":"eq","args":[{"op":"valueRef","id":"urn:aidd:typed:p:name"},{"op":"literal","value":"ok"}]},
                   {"op":"eq","args":[{"op":"valueRef","id":"urn:aidd:typed:p:color"},
                     {"op":"enumLiteral","typeId":"urn:aidd:typed:type:color","member":"RED"}]},
                   {"op":"contains","args":[{"op":"valueRef","id":"urn:aidd:typed:p:tags"},{"op":"literal","value":1}]},
                   {"op":"eq","args":[
                     {"op":"difference","args":[
                       {"op":"union","args":[
                         {"op":"valueRef","id":"urn:aidd:typed:p:tags"},
                         {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":2}]}
                       ]},
                       {"op":"intersect","args":[
                         {"op":"valueRef","id":"urn:aidd:typed:p:tags"},
                         {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":2}]}
                       ]}
                     ]},
                     {"op":"union","args":[
                       {"op":"difference","args":[
                         {"op":"valueRef","id":"urn:aidd:typed:p:tags"},
                         {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":2}]}
                       ]},
                       {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":2}]}
                     ]}
                   ]}
                 ]}},
                {"@id":"urn:aidd:typed:post","@type":"Postcondition","label":"accepted","status":"candidate",
                 "basis":"stated","expression":{"op":"eq","args":[
                   {"op":"valueRef","id":"urn:aidd:typed:r:accepted"},{"op":"literal","value":true}
                 ]}},
                {"@id":"urn:aidd:typed:contract","@type":"Contract","label":"typed contract","status":"candidate",
                 "basis":"stated","total":false,"defines":["urn:aidd:typed:op"],
                 "constrains":["urn:aidd:typed:pre","urn:aidd:typed:post"]}
              ]
            }
            """.trimIndent(),
        )
        val bounds = Bounds.defaultExploration()

        val verification = AlloyRunner().run(
            AlloyCompiler().compile(model, bounds, ClaimSelection.ACCEPTED_AND_CANDIDATE),
            bounds,
            Files.createTempDirectory("aidd-alloy-typed-values"),
            forceProvisional = true,
        )

        assertEquals(VerificationStatus.PROVISIONAL, verification.status, verification.diagnostics.joinToString())
        assertEquals(VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE, verification.boundedOutcome)
    }

    private fun pureFunctionFixture(): com.fasterxml.jackson.databind.node.ObjectNode =
        com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Path.of("examples/pure-function/model.jsonld").toFile()) as
            com.fasterxml.jackson.databind.node.ObjectNode

    private fun com.fasterxml.jackson.databind.node.ObjectNode.graphNode(
        id: String,
    ): com.fasterxml.jackson.databind.node.ObjectNode =
        path("@graph").first { it.path("@id").asText() == id } as
            com.fasterxml.jackson.databind.node.ObjectNode

    private fun explore(root: com.fasterxml.jackson.databind.node.ObjectNode): VerificationResult {
        val model = ModelParser().parse(root.toString())
        val bounds = Bounds.defaultExploration()
        return AlloyRunner().run(
            AlloyCompiler().compile(model, bounds, ClaimSelection.ACCEPTED_AND_CANDIDATE),
            bounds,
            Files.createTempDirectory("aidd-alloy-negative-contract"),
            forceProvisional = true,
        )
    }
}
