package io.github.youndie.kore.runtime

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs

/**
 * The macOS actual answers by declaration, and this holds it to the scenario that says so: the
 * answer is `Unavailable`, and its reason names the platform rather than four files it failed to
 * open. Read through a filesystem walk instead, the same answer would come back with a reason about
 * missing paths, and a laptop with a directory of that name mounted could turn it into "no limit".
 */
class MemoryBudgetMacosTest {
    @Test
    fun `macOS says it cannot answer and names the platform`() {
        val budget = assertIs<MemoryBudget.Unavailable>(containerMemoryBudget())

        assertContains(budget.reason, "macos")
    }
}
