package dev.aidd.render

import dev.aidd.model.ModelParser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `candidate renderer includes contract types totality and value references`() {
        val candidate = SpecRenderer().renderCandidates(
            ModelParser().parse(Path.of("examples/pure-function/model.jsonld")),
        )

        assertTrue(candidate.contains("Value type: `Int`"))
        assertTrue(candidate.contains("Total: `true`"))
        assertTrue(candidate.contains("sub("))
        assertTrue(candidate.contains("urn:aidd:withdraw:parameter:balance"))
    }

    @Test
    fun `business candidate renderer separates conceptual specification and implementation views`() {
        val model = ModelParser().parse(
            """
            {
              "@context":"https://aidd.dev/context/v1",
              "schemaVersion":"1.1",
              "specId":"business",
              "@graph":[
                {"@id":"urn:aidd:business:term:order","@type":"Term","label":"注文",
                 "status":"candidate","basis":"derived","generatedBy":"llm",
                 "evidencedBy":["urn:aidd:fact:order"]},
                {"@id":"urn:aidd:business:state:pending","@type":"State","label":"受付中",
                 "status":"candidate","basis":"derived","generatedBy":"llm",
                 "evidencedBy":["urn:aidd:fact:pending"]},
                {"@id":"urn:aidd:business:requirement:limit","@type":"Requirement","label":"上限を超えない",
                 "status":"candidate","basis":"derived","generatedBy":"llm",
                 "evidencedBy":["urn:aidd:fact:limit"]},
                {"@id":"urn:aidd:business:operation:place","@type":"Operation","label":"注文する",
                 "status":"candidate","basis":"derived","generatedBy":"llm",
                 "evidencedBy":["urn:aidd:fact:place"]},
                {"@id":"urn:aidd:business:error:limit","@type":"Error","label":"上限超過",
                 "status":"candidate","basis":"derived","generatedBy":"llm",
                 "evidencedBy":["urn:aidd:fact:error"]},
                {"@id":"urn:aidd:business:example:accepted","@type":"Example","label":"上限内の注文",
                 "status":"candidate","basis":"derived","generatedBy":"llm",
                 "evidencedBy":["urn:aidd:fact:test"]},
                {"@id":"urn:aidd:business:assumption:unit","@type":"Assumption","label":"上限の単位",
                 "status":"candidate","basis":"assumed","generatedBy":"llm",
                 "evidencedBy":["urn:aidd:fact:limit"]},
                {"@id":"urn:aidd:business:symbol:order","@type":"CodeSymbol","label":"OrderService",
                 "status":"candidate","basis":"observed",
                 "evidencedBy":["urn:aidd:fact:order"]},
                {"@id":"urn:aidd:business:req:accepted","@type":"Requirement","label":"承認済み規則",
                 "status":"accepted","basis":"observed",
                 "evidencedBy":["urn:aidd:fact:accepted"]}
              ]
            }
            """.trimIndent(),
        )

        val rendered = SpecRenderer().renderCandidateBusiness(model)

        listOf("## 概念", "### 用語", "### 状態", "## 仕様", "### 業務ルール", "### 操作", "### 例外", "### シナリオ", "### 未確定事項", "## 実装根拠")
            .forEach { heading -> assertTrue(rendered.contains(heading), heading) }
        assertTrue(rendered.contains("不確実性: `derived / llm / candidate`"))
        assertTrue(rendered.contains("urn:aidd:fact:limit"))
        assertFalse(rendered.contains("承認済み規則"))
    }

    @Test
    fun `business candidate renderer explicitly reports an empty candidate graph`() {
        val model = ModelParser().parse(
            """
            {"@context":"https://aidd.dev/context/v1","schemaVersion":"1.0","specId":"empty","@graph":[]}
            """.trimIndent(),
        )

        assertTrue(SpecRenderer().renderCandidateBusiness(model).contains("業務仕様候補はありません。"))
    }

    @Test
    fun `business candidate renderer is byte deterministic`() {
        val model = ModelParser().parse(Path.of("examples/pure-function/model.jsonld"))
        val renderer = SpecRenderer()

        assertEquals(
            renderer.renderCandidateBusiness(model),
            renderer.renderCandidateBusiness(model),
        )
    }
}
