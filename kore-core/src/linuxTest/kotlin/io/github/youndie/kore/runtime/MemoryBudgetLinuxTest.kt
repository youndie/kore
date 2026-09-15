package io.github.youndie.kore.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real reading, on a real Linux host, against a second implementation of the same question.
 *
 * The common tests decide what the content means; this one is the only place that answers whether
 * the files are where the code thinks they are — and the first thing it found is that they are not
 * where the obvious version of this test looked. **The root cgroup has no `memory.max`**: the kernel
 * does not create one for a cgroup that cannot be limited, so `/sys/fs/cgroup/memory.max` is absent
 * on an ordinary host and present inside a container, where the mount is the container's own
 * (non-root) cgroup. A check written against the root file reports "no cgroup here" on every
 * developer machine in the portfolio.
 *
 * It refuses to pass when it could not visit its subject: a host with no readable file in the chain
 * would otherwise make "unavailable" look like a verified answer.
 */
class MemoryBudgetLinuxTest {
    @Test
    fun `the answer is the tightest file in the process's own cgroup chain`() {
        val files = chainFiles()
        assertTrue(
            files.isNotEmpty(),
            "no readable memory.max anywhere in this process's cgroup chain; this test would prove nothing",
        )

        val budget = containerMemoryBudget()
        val limits = files.filterValues { it != "max" }.mapValues { (_, text) -> text.toLong() }

        if (limits.isEmpty()) {
            assertIs<MemoryBudget.Unbounded>(budget, "every file in the chain says max and the budget is ${budget.render()}")
        } else {
            val tightest = limits.minBy { it.value }
            val bounded = assertIs<MemoryBudget.Bounded>(budget, "a limit of ${tightest.value} was not reported")
            assertEquals(tightest.value, bounded.bytes)
            assertEquals(tightest.key, bounded.source)
        }
    }

    @Test
    fun `a mounted cgroup is never reported as unavailable`() {
        if (chainFiles().isEmpty()) return

        val budget = containerMemoryBudget()

        assertTrue(budget !is MemoryBudget.Unavailable, "a readable cgroup was not read: ${budget.render()}")
    }

    /**
     * A second implementation of the path walk — `substringAfter` on the v2 prefix and `dropLast` on
     * the segments, rather than the production `split(':')` and its ancestor list. Two ways of
     * reading one thing; only one of them is the code under test.
     */
    private fun chainFiles(): Map<String, String> {
        val line =
            readSmallFile("/proc/self/cgroup")
                ?.lineSequence()
                ?.firstOrNull { it.startsWith("0::") }
                ?: return emptyMap()
        val segments = line.substringAfter("0::").trim().split('/').filter { it.isNotEmpty() }
        val found = LinkedHashMap<String, String>()
        for (depth in segments.size downTo 0) {
            val prefix = segments.take(depth).joinToString("") { "/$it" }
            val path = "/sys/fs/cgroup$prefix/memory.max"
            readSmallFile(path)?.trim()?.let { found[path] = it }
        }
        return found
    }
}
