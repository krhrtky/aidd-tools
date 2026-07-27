package dev.aidd.generation

import dev.aidd.model.ClaimStatus
import dev.aidd.model.ModelParser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TypeScriptGeneratorTest {
    @Test
    fun `generates deterministic bigint Result function from accepted contract`() {
        val model = ModelParser().parse(Path.of("examples/pure-function/model.jsonld")).let { parsed ->
            parsed.copy(nodes = parsed.nodes.map { it.copy(status = ClaimStatus.ACCEPTED) })
        }
        val generator = TypeScriptGenerator()

        val first = generator.generate(model, "urn:aidd:withdraw:contract:withdraw")
        val second = generator.generate(model, "urn:aidd:withdraw:contract:withdraw")

        assertEquals(first, second)
        assertContains(first, "export type WithdrawResult =")
        assertContains(first, "{ ok: true; value: bigint }")
        assertContains(first, """{ ok: false; error: "InsufficientFunds" | "InvalidAmount" | "InvalidBalance" }""")
        assertContains(first, "export function withdraw(balance: bigint, amount: bigint): WithdrawResult")
        assertContains(first, """if (balance < 0n) return { ok: false, error: "InvalidBalance" };""")
        assertContains(first, "return { ok: true, value: balance - amount };")
    }
}
