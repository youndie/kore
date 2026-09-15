package io.github.youndie.kore.runtime

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Why kore's lever is the ceiling and not the target, asserted against the runtime rather than
 * argued from the documentation.
 *
 * Both settings exist, both take a number of bytes, and the one whose name fits the intention is the
 * wrong one: with `autotune` on — the default — the runtime recomputes `targetHeapBytes` from the
 * live set after a collection. A service that set the target from its container limit would find it
 * replaced by something derived from how much it happened to be holding.
 */
@OptIn(NativeRuntimeApi::class)
class HeapCeilingNativeTest {
    @Test
    fun `the ceiling survives a collection and the target does not`() {
        val target = GC.targetHeapBytes
        val max = GC.maxHeapBytes
        val autotune = GC.autotune
        try {
            GC.targetHeapBytes = CEILING
            GC.maxHeapBytes = CEILING

            // THE CONTROL: both setters took, before anything collected. Without this line the test
            // would also pass against a runtime that ignored the target outright, and the comment in
            // `HeapCeiling.native.kt` would be right about the API for the wrong reason.
            assertEquals(CEILING, GC.targetHeapBytes, "the target was not set at all")
            assertEquals(CEILING, GC.maxHeapBytes, "the ceiling was not set at all")

            // Enough churn for the autotuner to have an opinion, and a collection to act on it.
            repeat(4) {
                val ballast = ArrayList<ByteArray>()
                repeat(64) { ballast += ByteArray(64 * 1024) }
                ballast.clear()
                GC.collect()
            }

            assertEquals(CEILING, GC.maxHeapBytes, "the ceiling did not survive four collections")
            assertTrue(
                GC.autotune,
                "this test is about what autotune does; with it off the target would survive and the " +
                    "reason for preferring the ceiling would be untested",
            )
            // Measured on 2026-09-15, linuxX64, Kotlin 2.4.10: the target set to 201 326 592 came
            // back as **5 242 880** — the autotuner's floor, derived from a live set of nearly
            // nothing — while the ceiling was still 201 326 592. The two settings do not differ in
            // degree; one of them is simply not a place to put a container limit.
            assertTrue(
                GC.targetHeapBytes != CEILING,
                "the target survived autotune, so kore's comment about why it uses the ceiling is wrong " +
                    "rather than merely cautious: target=${GC.targetHeapBytes}",
            )
        } finally {
            GC.targetHeapBytes = target
            GC.maxHeapBytes = max
            GC.autotune = autotune
        }
    }

    @Test
    fun `applyHeapCeiling reports what the runtime ended up with`() {
        val max = GC.maxHeapBytes
        try {
            val outcome = applyHeapCeiling(CEILING)

            assertTrue(outcome.applied, "the ceiling was not applied: $outcome")
            assertEquals(CEILING, outcome.ceilingBytes)
            assertEquals(CEILING, heapCeiling())
        } finally {
            GC.maxHeapBytes = max
        }
    }

    private companion object {
        const val CEILING = 192L * 1024 * 1024
    }
}
