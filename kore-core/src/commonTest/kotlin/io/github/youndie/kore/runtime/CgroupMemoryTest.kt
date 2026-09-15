package io.github.youndie.kore.runtime

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The cgroup reading, against a filesystem written out in the test.
 *
 * Every case here is one a container actually presents, and three of them are ones a reader would
 * not invent: the same limit reached through two different layouts, an ancestor that is stricter
 * than the cgroup the process sits in, and a v1 "no limit" that is a nineteen-digit number.
 */
class CgroupMemoryTest {
    private class Fs(private val files: Map<String, String>) {
        val asked: MutableList<String> = mutableListOf()

        fun read(path: String): String? {
            asked += path
            return files[path]
        }
    }

    @Test
    fun `cgroup v2 inside a container reads the limit at the root of the mount`() {
        val fs =
            Fs(
                mapOf(
                    // What a container with a cgroup namespace shows: it is at the root of its own view.
                    "/proc/self/cgroup" to "0::/\n",
                    "/sys/fs/cgroup/memory.max" to "201326592\n",
                ),
            )

        val budget = resolveMemoryBudget(fs::read)

        val bounded = assertIs<MemoryBudget.Bounded>(budget)
        assertEquals(201326592L, bounded.bytes)
        assertEquals("/sys/fs/cgroup/memory.max", bounded.source)
        assertTrue("/proc/self/cgroup" in fs.asked, "the process's own cgroup was never looked up")
    }

    @Test
    fun `no limit is answered as no limit and not as a number`() {
        val fs = Fs(mapOf("/proc/self/cgroup" to "0::/\n", "/sys/fs/cgroup/memory.max" to "max\n"))

        assertIs<MemoryBudget.Unbounded>(resolveMemoryBudget(fs::read))
    }

    /**
     * The case that makes the walk necessary rather than tidy: a pod-level limit sits on the
     * ancestor, the container's own cgroup says `max`, and reading only the leaf reports a service
     * with no limit at all.
     */
    @Test
    fun `a stricter ancestor is the limit that will be enforced`() {
        val fs =
            Fs(
                mapOf(
                    "/proc/self/cgroup" to "0::/kubepods/podabc/container\n",
                    "/sys/fs/cgroup/kubepods/podabc/container/memory.max" to "max\n",
                    "/sys/fs/cgroup/kubepods/podabc/memory.max" to "104857600\n",
                    "/sys/fs/cgroup/memory.max" to "max\n",
                ),
            )

        val bounded = assertIs<MemoryBudget.Bounded>(resolveMemoryBudget(fs::read))

        assertEquals(104857600L, bounded.bytes)
        assertEquals("/sys/fs/cgroup/kubepods/podabc/memory.max", bounded.source)
    }

    @Test
    fun `the tightest of several limits wins wherever it sits`() {
        val fs =
            Fs(
                mapOf(
                    "/proc/self/cgroup" to "0::/kubepods/podabc/container\n",
                    "/sys/fs/cgroup/kubepods/podabc/container/memory.max" to "52428800\n",
                    "/sys/fs/cgroup/kubepods/podabc/memory.max" to "104857600\n",
                ),
            )

        assertEquals(52428800L, assertIs<MemoryBudget.Bounded>(resolveMemoryBudget(fs::read)).bytes)
    }

    @Test
    fun `cgroup v1 is read when there is no v2 line`() {
        val fs =
            Fs(
                mapOf(
                    "/proc/self/cgroup" to "4:cpu,cpuacct:/docker/abc\n3:memory:/docker/abc\n",
                    "/sys/fs/cgroup/memory/docker/abc/memory.limit_in_bytes" to "268435456\n",
                ),
            )

        val bounded = assertIs<MemoryBudget.Bounded>(resolveMemoryBudget(fs::read))

        assertEquals(268435456L, bounded.bytes)
        assertEquals("/sys/fs/cgroup/memory/docker/abc/memory.limit_in_bytes", bounded.source)
    }

    /** v1 spells "unlimited" as a page-aligned `LONG_MAX`, which is not nine exabytes of memory. */
    @Test
    fun `the v1 sentinel is no limit rather than an enormous one`() {
        val fs =
            Fs(
                mapOf(
                    "/proc/self/cgroup" to "3:memory:/\n",
                    "/sys/fs/cgroup/memory/memory.limit_in_bytes" to "$V1_UNLIMITED\n",
                ),
            )

        assertIs<MemoryBudget.Unbounded>(resolveMemoryBudget(fs::read))
    }

    @Test
    fun `nothing readable is unavailable and the reason names what was tried`() {
        val fs = Fs(emptyMap())

        val unavailable = assertIs<MemoryBudget.Unavailable>(resolveMemoryBudget(fs::read))

        assertContains(unavailable.reason, "/sys/fs/cgroup/memory.max")
        assertContains(unavailable.reason, "/proc/self/cgroup")
    }

    @Test
    fun `a file that does not hold a number is not a limit`() {
        val fs = Fs(mapOf("/proc/self/cgroup" to "0::/\n", "/sys/fs/cgroup/memory.max" to "not a number\n"))

        assertIs<MemoryBudget.Unavailable>(resolveMemoryBudget(fs::read))
    }

    /**
     * The rendering is part of the contract: the one thing this type exists to prevent is an
     * unreadable cgroup being read as "no limit", and rendering is where that would happen.
     */
    @Test
    fun `an unavailable budget never renders as no limit`() {
        val rendered = MemoryBudget.Unavailable("nothing was readable").render()

        assertContains(rendered, "unknown")
        assertTrue("no limit" !in rendered, "unavailable rendered as if it were unbounded: $rendered")
    }

    @Test
    fun `a limit renders as the unit a chart is written in`() {
        assertContains(MemoryBudget.Bounded(201326592L, "/sys/fs/cgroup/memory.max").render(), "192 MiB")
    }
}
