package dev.aidd.alloy

import dev.aidd.model.ModelParser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlloyCompilerTest {
    @Test
    fun `compiler emits deterministic signatures transitions and constraints`() {
        val model = ModelParser().parse(
            """
            {
              "@context": "https://aidd.dev/context/v1",
              "schemaVersion": "1.0",
              "specId": "order",
              "@graph": [
                {"@id":"urn:aidd:order:state:draft","@type":"State","label":"Draft","status":"accepted","basis":"stated"},
                {"@id":"urn:aidd:order:state:confirmed","@type":"State","label":"Confirmed","status":"accepted","basis":"stated"},
                {
                  "@id":"urn:aidd:order:transition:confirm",
                  "@type":"Transition",
                  "label":"confirm",
                  "status":"accepted",
                  "basis":"stated",
                  "transitionsFrom":["urn:aidd:order:state:draft"],
                  "transitionsTo":["urn:aidd:order:state:confirmed"]
                },
                {
                  "@id":"urn:aidd:order:constraint:distinct",
                  "@type":"Invariant",
                  "label":"states differ",
                  "status":"accepted",
                  "basis":"stated",
                  "expression":{"op":"neq","args":[
                    {"op":"ref","id":"urn:aidd:order:state:draft"},
                    {"op":"ref","id":"urn:aidd:order:state:confirmed"}
                  ]}
                }
              ]
            }
            """.trimIndent(),
        )

        val alloy = AlloyCompiler().compile(model, Bounds.defaultExploration())

        assertTrue(alloy.contains("one sig State_Draft extends AiddState {}"))
        assertTrue(alloy.contains("from = State_Draft"))
        assertTrue(alloy.contains("one sig AiddRuntime"))
        assertTrue(alloy.contains("fact TransitionTrace"))
        assertTrue(alloy.contains("assert Constraint_states_differ"))
        assertTrue(alloy.contains("check Constraint_states_differ for 3 but 4 Int, 10 steps"))
        assertTrue(alloy.contains("run AiddModel for 3 but 4 Int, 10 steps"))
        assertEquals(alloy, AlloyCompiler().compile(model, Bounds.defaultExploration()))
    }

    @Test
    fun `candidate exploration includes accepted and candidate claims but excludes rejected claims`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.0",
              "specId":"selection",
              "@graph":[
                {"@id":"urn:aidd:selection:state:accepted","@type":"State","label":"Accepted","status":"accepted","basis":"stated"},
                {"@id":"urn:aidd:selection:state:candidate","@type":"State","label":"Candidate","status":"candidate","basis":"derived"},
                {"@id":"urn:aidd:selection:state:rejected","@type":"State","label":"Rejected","status":"rejected","basis":"derived"}
              ]
            }
            """.trimIndent(),
        )

        val verification = AlloyCompiler().compile(model, Bounds.defaultExploration())
        val exploration = AlloyCompiler().compile(
            model,
            Bounds.defaultExploration(),
            ClaimSelection.ACCEPTED_AND_CANDIDATE,
        )

        assertTrue(verification.contains("State_Accepted"))
        assertTrue(!verification.contains("State_Candidate"))
        assertTrue(exploration.contains("State_Accepted"))
        assertTrue(exploration.contains("State_Candidate"))
        assertTrue(!exploration.contains("State_Rejected"))
    }

    @Test
    fun `compiler emits pure function contract proof obligations`() {
        val model = ModelParser().parse(Path.of("examples/pure-function/model.jsonld"))

        val alloy = AlloyCompiler().compile(
            model,
            Bounds.defaultExploration(),
            ClaimSelection.ACCEPTED_AND_CANDIDATE,
        )

        assertTrue(alloy.contains("_PreSatisfiable"))
        assertTrue(alloy.contains("_ValidResultExactlyOne"))
        assertTrue(alloy.contains("_ValidHasNoError"))
        assertTrue(alloy.contains("_ErrorsDisjoint"))
        assertTrue(alloy.contains("_TotalInvalidHasExactlyOneError"))
        assertTrue(alloy.contains(".sub["))
    }
}
