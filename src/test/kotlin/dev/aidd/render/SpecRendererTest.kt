package dev.aidd.render

import dev.aidd.model.ModelParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecRendererTest {
    @Test
    fun `renderer includes accepted nodes and separates candidates`() {
        val model = ModelParser().parse(
            """
            {
              "@context": "https://aidd.dev/context/v1",
              "schemaVersion": "1.0",
              "specId": "sample",
              "@graph": [
                {"@id":"urn:aidd:sample:req:accepted","@type":"Requirement","label":"Accepted rule","status":"accepted","basis":"stated"},
                {"@id":"urn:aidd:sample:req:candidate","@type":"Requirement","label":"Candidate rule","status":"candidate","basis":"derived"}
              ]
            }
            """.trimIndent(),
        )

        val accepted = SpecRenderer().renderAccepted(model)
        val candidate = SpecRenderer().renderCandidates(model)

        assertTrue(accepted.contains("Accepted rule"))
        assertFalse(accepted.contains("Candidate rule"))
        assertTrue(candidate.contains("Candidate rule"))
    }
}

