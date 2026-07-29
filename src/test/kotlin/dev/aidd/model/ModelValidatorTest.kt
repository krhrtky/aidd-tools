package dev.aidd.model

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelValidatorTest {
    @Test
    fun `path validation converts parser failures to invalid model diagnostics`() {
        val directory = Files.createTempDirectory("aidd-parser-failure")
        val model = directory.resolve("model.jsonld")
        model.writeText("""{"unexpected":true}""")

        val result = ModelValidator().validate(model)

        assertEquals(null, result.model)
        assertEquals(listOf("INVALID_MODEL"), result.diagnostics.map(Diagnostic::code))
    }

    @Test
    fun `validation reports generator transition approval provenance and evidence violations`() {
        val directory = Files.createTempDirectory("aidd-multiple-invalid")
        val evidence = directory.resolve("evidence.md")
        evidence.writeText("evidence")
        val model = ModelParser().parse(
            """
            {
              "@context":"invalid-context",
              "schemaVersion":"1.0",
              "specId":"invalid spec",
              "@graph":[
                {
                  "@id":"invalid-id",
                  "@type":"Unknown",
                  "status":"candidate",
                  "basis":"derived",
                  "generatedBy":"machine"
                },
                {
                  "@id":"urn:aidd:invalid:transition:missing",
                  "@type":"Transition",
                  "status":"candidate",
                  "basis":"derived",
                  "dependsOn":["urn:aidd:invalid:missing"]
                },
                {
                  "@id":"urn:aidd:invalid:requirement:endpoint",
                  "@type":"Requirement",
                  "status":"candidate",
                  "basis":"derived",
                  "derivesFrom":["urn:aidd:invalid:transition:missing"]
                },
                {
                  "@id":"urn:aidd:invalid:transition:wrong",
                  "@type":"Transition",
                  "status":"candidate",
                  "basis":"derived",
                  "transitionsFrom":["urn:aidd:invalid:requirement:endpoint"],
                  "transitionsTo":["urn:aidd:invalid:requirement:endpoint"]
                },
                {
                  "@id":"urn:aidd:invalid:assumption:accepted",
                  "@type":"Assumption",
                  "status":"accepted",
                  "basis":"assumed"
                },
                {
                  "@id":"urn:aidd:invalid:decision:accepted",
                  "@type":"HumanDecision",
                  "status":"accepted",
                  "basis":"stated",
                  "generatedBy":"harness",
                  "defines":["urn:aidd:invalid:assumption:accepted"]
                },
                {
                  "@id":"urn:aidd:invalid:evidence:span",
                  "@type":"Requirement",
                  "status":"candidate",
                  "basis":"stated",
                  "evidence":[{
                    "path":"evidence.md",
                    "startLine":2,
                    "startColumn":2,
                    "endLine":1,
                    "endColumn":1,
                    "sha256":"invalid"
                  }]
                },
                {
                  "@id":"urn:aidd:invalid:parameter:v11",
                  "@type":"Parameter",
                  "status":"candidate",
                  "basis":"derived",
                  "valueType":{"kind":"int"},
                  "derivesFrom":["urn:aidd:invalid:requirement:endpoint"]
                }
              ]
            }
            """.trimIndent(),
        )

        val codes = ModelValidator().validate(model, directory).diagnostics.map(Diagnostic::code).toSet()

        setOf(
            "INVALID_CONTEXT",
            "INVALID_SPEC_ID",
            "UNSTABLE_ID",
            "UNKNOWN_NODE_TYPE",
            "INVALID_GENERATOR",
            "INVALID_TRANSITION_ENDPOINT",
            "INVALID_TRANSITION_ENDPOINT_TYPE",
            "DANGLING_REFERENCE",
            "ACCEPTED_ASSUMPTION_REQUIRES_DECISION",
            "INVALID_HUMAN_DECISION",
            "MISSING_PROVENANCE_EVIDENCE",
            "INVALID_SOURCE_SPAN",
            "FEATURE_REQUIRES_SCHEMA_1_1",
        ).forEach { code -> assertTrue(code in codes, code) }
    }

    @Test
    fun `schema 1_1 approval bindings report invalid targets hashes and source`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.1",
              "specId":"approval-errors",
              "@graph":[
                {
                  "@id":"urn:aidd:approval-errors:requirement:one",
                  "@type":"Requirement",
                  "status":"candidate",
                  "basis":"derived",
                  "derivesFrom":["urn:aidd:approval-errors:assumption:one"]
                },
                {
                  "@id":"urn:aidd:approval-errors:assumption:one",
                  "@type":"Assumption",
                  "status":"candidate",
                  "basis":"assumed",
                  "derivesFrom":["urn:aidd:approval-errors:requirement:one"]
                },
                {
                  "@id":"urn:aidd:approval-errors:decision:targets",
                  "@type":"HumanDecision",
                  "status":"accepted",
                  "basis":"stated",
                  "generatedBy":"human",
                  "evidence":[{
                    "path":"decision.md",
                    "startLine":1,
                    "startColumn":1,
                    "endLine":1,
                    "endColumn":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }],
                  "defines":["urn:aidd:approval-errors:assumption:one"],
                  "constrains":["urn:aidd:approval-errors:requirement:one"],
                  "approvedClaimHashes":{
                    "urn:aidd:approval-errors:assumption:one":"invalid",
                    "urn:aidd:approval-errors:requirement:one":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                  },
                  "sourceModelSha256":"invalid"
                },
                {
                  "@id":"urn:aidd:approval-errors:decision:mismatch",
                  "@type":"HumanDecision",
                  "status":"accepted",
                  "basis":"stated",
                  "generatedBy":"human",
                  "evidence":[{
                    "path":"decision.md",
                    "startLine":1,
                    "startColumn":1,
                    "endLine":1,
                    "endColumn":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }],
                  "defines":["urn:aidd:approval-errors:requirement:one"],
                  "approvedClaimHashes":{
                    "urn:aidd:approval-errors:assumption:one":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                  },
                  "sourceModelSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                }
              ]
            }
            """.trimIndent(),
        )

        val codes = ModelValidator().validate(model).diagnostics.map(Diagnostic::code).toSet()

        setOf(
            "INVALID_APPROVAL_SOURCE_HASH",
            "INVALID_APPROVAL_BINDINGS",
            "ASSUMPTION_REQUIRES_EXPLICIT_APPROVAL",
            "INVALID_ASSUMPTION_APPROVAL",
            "INVALID_APPROVED_CLAIM_HASH",
            "STALE_HUMAN_DECISION",
        ).forEach { code -> assertTrue(code in codes, code) }
    }

    @Test
    fun `llm candidate cannot claim directly observed business meaning`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.1",
              "specId":"candidate",
              "@graph":[{
                "@id":"urn:aidd:candidate:requirement:purpose",
                "@type":"Requirement",
                "label":"business purpose",
                "status":"candidate",
                "basis":"observed",
                "generatedBy":"llm",
                "evidencedBy":["urn:aidd:fact:purpose"]
              }]
            }
            """.trimIndent(),
        )

        val diagnostics = ModelValidator().validate(model).diagnostics

        assertTrue(diagnostics.any { it.code == "INVALID_LLM_CANDIDATE_BASIS" })
    }

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

    @Test
    fun `version 1_1 contract model provides typed parameters result and deterministic relations`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.1",
              "specId":"withdraw",
              "@graph":[
                {
                  "@id":"urn:aidd:withdraw:requirement:source","@type":"Requirement",
                  "status":"candidate","basis":"stated","label":"withdraw requirement",
                  "evidence":[{
                    "path":"requirements.md","startLine":1,"startColumn":1,"endLine":1,"endColumn":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }]
                },
                {
                  "@id":"urn:aidd:withdraw:type:mode","@type":"Type",
                  "status":"candidate","basis":"derived","members":["NORMAL","FORCED"],
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"]
                },
                {
                  "@id":"urn:aidd:withdraw:param:balance","@type":"Parameter",
                  "status":"candidate","basis":"derived","valueType":{"kind":"int"},
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"]
                },
                {
                  "@id":"urn:aidd:withdraw:param:amount","@type":"Parameter",
                  "status":"candidate","basis":"derived","valueType":{"kind":"int"},
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"]
                },
                {
                  "@id":"urn:aidd:withdraw:param:mode","@type":"Parameter",
                  "status":"candidate","basis":"derived",
                  "valueType":{"kind":"enum","typeId":"urn:aidd:withdraw:type:mode"},
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"]
                },
                {
                  "@id":"urn:aidd:withdraw:result:new-balance","@type":"Result",
                  "status":"candidate","basis":"derived","valueType":{"kind":"int"},
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"]
                },
                {
                  "@id":"urn:aidd:withdraw:pre:positive","@type":"Precondition",
                  "status":"candidate","basis":"derived",
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"],
                  "expression":{"op":"gt","args":[
                    {"op":"valueRef","id":"urn:aidd:withdraw:param:amount"},
                    {"op":"literal","value":0}
                  ]}
                },
                {
                  "@id":"urn:aidd:withdraw:post:subtract","@type":"Postcondition",
                  "status":"candidate","basis":"derived",
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"],
                  "expression":{"op":"and","args":[
                    {"op":"eq","args":[
                      {"op":"valueRef","id":"urn:aidd:withdraw:result:new-balance"},
                      {"op":"sub","args":[
                        {"op":"valueRef","id":"urn:aidd:withdraw:param:balance"},
                        {"op":"valueRef","id":"urn:aidd:withdraw:param:amount"}
                      ]}
                    ]},
                    {"op":"eq","args":[
                      {"op":"valueRef","id":"urn:aidd:withdraw:param:mode"},
                      {"op":"enumLiteral","typeId":"urn:aidd:withdraw:type:mode","member":"NORMAL"}
                    ]}
                  ]}
                },
                {
                  "@id":"urn:aidd:withdraw:error:insufficient","@type":"Error",
                  "status":"candidate","basis":"derived",
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"],
                  "expression":{"op":"gt","args":[
                    {"op":"valueRef","id":"urn:aidd:withdraw:param:amount"},
                    {"op":"valueRef","id":"urn:aidd:withdraw:param:balance"}
                  ]}
                },
                {
                  "@id":"urn:aidd:withdraw:operation:withdraw","@type":"Operation",
                  "status":"candidate","basis":"derived",
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"],
                  "accepts":[
                    "urn:aidd:withdraw:param:balance",
                    "urn:aidd:withdraw:param:amount",
                    "urn:aidd:withdraw:param:mode"
                  ],
                  "returns":["urn:aidd:withdraw:result:new-balance"],
                  "mayFailWith":["urn:aidd:withdraw:error:insufficient"]
                },
                {
                  "@id":"urn:aidd:withdraw:contract:withdraw","@type":"Contract",
                  "status":"candidate","basis":"derived","total":false,
                  "derivesFrom":["urn:aidd:withdraw:requirement:source"],
                  "defines":["urn:aidd:withdraw:operation:withdraw"],
                  "constrains":[
                    "urn:aidd:withdraw:pre:positive",
                    "urn:aidd:withdraw:post:subtract",
                    "urn:aidd:withdraw:error:insufficient"
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val result = ModelValidator().validate(model)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
        val operation = result.model!!.nodes.single { it.type == "Operation" }
        assertEquals(
            listOf(
                "urn:aidd:withdraw:param:balance",
                "urn:aidd:withdraw:param:amount",
                "urn:aidd:withdraw:param:mode",
            ),
            operation.relations.getValue("accepts"),
        )
        assertEquals(ValueKind.ENUM, result.model.nodes.single { it.id.endsWith("param:mode") }.valueType?.kind)
        assertEquals(false, result.model.nodes.single { it.type == "Contract" }.total)
    }

    @Test
    fun `version 1_1 rejects nested collections and invalid contract graph`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.1",
              "specId":"invalid-contract",
              "@graph":[
                {
                  "@id":"urn:aidd:invalid:requirement:source","@type":"Requirement",
                  "status":"candidate","basis":"stated",
                  "evidence":[{
                    "path":"requirements.md","startLine":1,"startColumn":1,"endLine":1,"endColumn":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }]
                },
                {
                  "@id":"urn:aidd:invalid:param:nested","@type":"Parameter",
                  "status":"candidate","basis":"derived",
                  "derivesFrom":["urn:aidd:invalid:requirement:source"],
                  "valueType":{"kind":"list","elementType":{"kind":"set","elementType":{"kind":"int"}}}
                },
                {
                  "@id":"urn:aidd:invalid:result:value","@type":"Result",
                  "status":"candidate","basis":"derived","valueType":{"kind":"int"},
                  "derivesFrom":["urn:aidd:invalid:requirement:source"]
                },
                {
                  "@id":"urn:aidd:invalid:operation:x","@type":"Operation",
                  "status":"candidate","basis":"derived",
                  "derivesFrom":["urn:aidd:invalid:requirement:source"],
                  "accepts":["urn:aidd:invalid:result:value"],
                  "returns":[]
                },
                {
                  "@id":"urn:aidd:invalid:contract:x","@type":"Contract",
                  "status":"candidate","basis":"derived",
                  "derivesFrom":["urn:aidd:invalid:requirement:source"],
                  "defines":["urn:aidd:invalid:result:value"]
                }
              ]
            }
            """.trimIndent(),
        )

        val codes = ModelValidator().validate(model).diagnostics.map(Diagnostic::code).toSet()

        assertTrue("NESTED_COLLECTION_UNSUPPORTED" in codes)
        assertTrue("INVALID_RELATION_TARGET_TYPE" in codes)
        assertTrue("INVALID_OPERATION_RESULT" in codes)
        assertTrue("MISSING_CONTRACT_TOTAL" in codes)
        assertTrue("INVALID_CONTRACT_OPERATION" in codes)
        assertTrue("INVALID_CONTRACT_POSTCONDITION" in codes)
    }

    @Test
    fun `collection expressions are typed and unsupported operations fail closed`() {
        fun diagnosticsFor(expression: String): List<Diagnostic> {
            val model = ModelParser().parse(
                """
                {
                  "@context":"https://aidd.dev/context/v1",
                  "schemaVersion":"1.1",
                  "specId":"collections",
                  "@graph":[
                    {
                      "@id":"urn:aidd:collections:requirement:source","@type":"Requirement",
                      "status":"candidate","basis":"stated",
                      "evidence":[{
                        "path":"requirements.md","startLine":1,"startColumn":1,"endLine":1,"endColumn":1,
                        "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                      }]
                    },
                    {
                      "@id":"urn:aidd:collections:constraint:x","@type":"Constraint",
                      "status":"candidate","basis":"derived",
                      "derivesFrom":["urn:aidd:collections:requirement:source"],
                      "expression":$expression
                    }
                  ]
                }
                """.trimIndent(),
            )
            return ModelValidator().validate(model).diagnostics
        }
        val intList = """{"op":"listLiteral","valueType":{"kind":"int"},"args":[
            {"op":"literal","value":1},{"op":"literal","value":2}]}""".trimIndent()
        val collectionExpression =
            """{"op":"and","args":[
              {"op":"eq","args":[{"op":"size","args":[$intList]},{"op":"literal","value":2}]},
              {"op":"eq","args":[{"op":"index","args":[$intList,{"op":"literal","value":0}]},{"op":"literal","value":1}]},
              {"op":"contains","args":[{"op":"append","args":[$intList,{"op":"literal","value":3}]},{"op":"literal","value":3}]},
              {"op":"eq","args":[
                {"op":"slice","args":[{"op":"concat","args":[$intList,$intList]},{"op":"literal","value":0},{"op":"literal","value":2}]},
                $intList
              ]},
              {"op":"eq","args":[
                {"op":"difference","args":[
                  {"op":"union","args":[
                    {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":1}]},
                    {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":2}]}
                  ]},
                  {"op":"intersect","args":[
                    {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":2}]},
                    {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":2}]}
                  ]}
                ]},
                {"op":"setLiteral","valueType":{"kind":"int"},"args":[{"op":"literal","value":1}]}
              ]},
              {"op":"eq","args":[
                {"op":"add","args":[{"op":"literal","value":1},{"op":"mul","args":[{"op":"literal","value":2},{"op":"literal","value":3}]}]},
                {"op":"literal","value":7}
              ]}
            ]}""".trimIndent()

        assertTrue(diagnosticsFor(collectionExpression).isEmpty())
        assertTrue(diagnosticsFor("""{"op":"regex","args":[]}""").any { it.code == "UNSUPPORTED_EXPRESSION" })
        assertTrue(diagnosticsFor("""{"op":"div","args":[{"op":"literal","value":1},{"op":"literal","value":1}]}""")
            .any { it.code == "UNSUPPORTED_EXPRESSION" })
        assertTrue(diagnosticsFor("""{"op":"map","args":[]}""").any { it.code == "UNSUPPORTED_EXPRESSION" })
        assertTrue(
            diagnosticsFor(
                """{"op":"eq","args":[
                  {"op":"index","args":[$intList,{"op":"literal","value":2}]},
                  {"op":"literal","value":1}
                ]}""",
            ).any { it.code == "INDEX_OUT_OF_RANGE" },
        )
    }

    @Test
    fun `version 1_0 remains valid but cannot silently use 1_1 contract features`() {
        val version10 = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"compatibility",
              "@graph":[{
                "@id":"urn:aidd:compatibility:param:x","@type":"Parameter",
                "status":"candidate","basis":"stated","valueType":{"kind":"int"},
                "evidence":[{
                  "path":"requirements.md","startLine":1,"startColumn":1,"endLine":1,"endColumn":1,
                  "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }]
              }]
            }
            """.trimIndent(),
        )

        val diagnostics = ModelValidator().validate(version10).diagnostics

        assertTrue(diagnostics.any { it.code == "FEATURE_REQUIRES_SCHEMA_1_1" })
    }
}
