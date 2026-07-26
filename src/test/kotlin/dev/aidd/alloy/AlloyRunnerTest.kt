package dev.aidd.alloy

import dev.aidd.model.ModelParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AlloyRunnerTest {
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
}
