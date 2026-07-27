package dev.aidd.refinement

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.aidd.alloy.AlloyRunner
import dev.aidd.alloy.Bounds
import dev.aidd.alloy.VerificationStatus
import dev.aidd.model.ClaimStatus
import dev.aidd.model.ModelParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefinementCompilerTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `observed subtraction refines accepted withdraw contract`() {
        val directory = Files.createTempDirectory("aidd-refinement-valid")
        val model = acceptedWithdrawModel()
        val observed = parseObserved(directory, "sub")
        val bounds = approvedBounds()

        val alloy = RefinementCompiler().compile(model, observed, contractId, bounds)
        val result = AlloyRunner().run(alloy, bounds, directory.resolve("counterexamples"))

        assertEquals(VerificationStatus.NO_COUNTEREXAMPLE_WITHIN_SCOPE, result.status)
    }

    @Test
    fun `mutated addition produces a refinement counterexample`() {
        val directory = Files.createTempDirectory("aidd-refinement-mutated")
        val model = acceptedWithdrawModel()
        val observed = parseObserved(directory, "add")
        val bounds = approvedBounds()

        val alloy = RefinementCompiler().compile(model, observed, contractId, bounds)
        val result = AlloyRunner().run(alloy, bounds, directory.resolve("counterexamples"))

        assertEquals(VerificationStatus.COUNTEREXAMPLE, result.status)
    }

    @Test
    fun `guard and boundary mutations are rejected by the refinement gate`() {
        val model = acceptedWithdrawModel()
        val bounds = approvedBounds()
        val mutations = mapOf(
            "missing-invalid-amount-zero" to observedFacts("sub").replace(
                """"op" : "lte"""",
                """"op" : "lt"""",
            ),
            "reversed-insufficient-funds" to observedFacts("sub").replace(
                """"op" : "gt"""",
                """"op" : "lt"""",
            ),
        )

        mutations.forEach { (name, facts) ->
            val directory = Files.createTempDirectory("aidd-refinement-$name")
            val path = directory.resolve("facts.json")
            path.writeText(facts)
            val observed = ObservedContractParser().parse(path, "withdraw")
            val alloy = RefinementCompiler().compile(model, observed, contractId, bounds)
            val result = AlloyRunner().run(alloy, bounds, directory.resolve("counterexamples"))

            assertEquals(VerificationStatus.COUNTEREXAMPLE, result.status, name)
        }
    }

    @Test
    fun `type mismatch fails closed before Alloy execution`() {
        val directory = Files.createTempDirectory("aidd-refinement-type")
        val model = acceptedWithdrawModel()
        val path = directory.resolve("facts.json")
        path.writeText(observedFacts("sub", resultKind = "string"))
        val observed = ObservedContractParser().parse(path, "withdraw")

        assertFailsWith<UnsupportedRefinementException> {
            RefinementCompiler().compile(model, observed, contractId, approvedBounds())
        }
    }

    private fun acceptedWithdrawModel() =
        ModelParser().parse(Path.of("examples/pure-function/model.jsonld")).let { model ->
            model.copy(nodes = model.nodes.map { it.copy(status = ClaimStatus.ACCEPTED) })
        }

    private fun parseObserved(directory: Path, resultOperator: String): ObservedContract {
        val path = directory.resolve("facts.json")
        path.writeText(observedFacts(resultOperator))
        return ObservedContractParser().parse(path, "withdraw")
    }

    private fun observedFacts(resultOperator: String, resultKind: String = "int"): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapper.readTree(
                """
                {
                  "schemaVersion":"1.0",
                  "language":"typescript",
                  "extractor":{"name":"typescript-compiler-api","version":"6.0.3"},
                  "repositorySha256":"${"a".repeat(64)}",
                  "facts":[{
                    "id":"urn:aidd:code:withdraw",
                    "kind":"observedContract",
                    "name":"withdraw",
                    "qualifiedName":"withdraw",
                    "status":"accepted",
                    "basis":"observed",
                    "source":{
                      "path":"withdraw.ts",
                      "startLine":1,
                      "startColumn":1,
                      "endLine":12,
                      "endColumn":2,
                      "sha256":"${"b".repeat(64)}"
                    },
                    "details":{
                      "schemaVersion":"1.0",
                      "contractIds":["$contractId"],
                      "operation":"withdraw",
                      "parameters":[
                        {"name":"balance","valueType":{"kind":"int"}},
                        {"name":"amount","valueType":{"kind":"int"}}
                      ],
                      "resultType":{"kind":"$resultKind"},
                      "errorTypes":["InvalidBalance","InvalidAmount","InsufficientFunds"],
                      "cases":[
                        {
                          "when":{"op":"lt","args":[{"op":"valueRef","name":"balance"},{"op":"intLiteral","value":"0"}]},
                          "outcome":{"kind":"error","error":"InvalidBalance"}
                        },
                        {
                          "when":{"op":"lte","args":[{"op":"valueRef","name":"amount"},{"op":"intLiteral","value":"0"}]},
                          "outcome":{"kind":"error","error":"InvalidAmount"}
                        },
                        {
                          "when":{"op":"gt","args":[{"op":"valueRef","name":"amount"},{"op":"valueRef","name":"balance"}]},
                          "outcome":{"kind":"error","error":"InsufficientFunds"}
                        },
                        {
                          "when":{"op":"literal","value":true},
                          "outcome":{
                            "kind":"success",
                            "value":{"op":"$resultOperator","args":[
                              {"op":"valueRef","name":"balance"},
                              {"op":"valueRef","name":"amount"}
                            ]}
                          }
                        }
                      ]
                    }
                  }],
                  "diagnostics":[]
                }
                """.trimIndent(),
            ),
        )

    private fun approvedBounds() = Bounds(
        globalScope = 3,
        intBitwidth = 4,
        maxTraceSteps = 10,
        approved = true,
        approvedBy = "test",
        decisionId = "urn:aidd:decision:test-bounds",
    )

    companion object {
        private const val contractId = "urn:aidd:withdraw:contract:withdraw"
    }
}
