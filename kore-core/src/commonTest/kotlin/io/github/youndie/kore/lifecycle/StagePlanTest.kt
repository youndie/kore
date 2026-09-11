package io.github.youndie.kore.lifecycle

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class StagePlanTest {
    @Test
    fun `a plan out of specification order is refused where it is written`() {
        assertFailsWith<IllegalArgumentException> {
            ShutdownSequence(
                listOf(
                    StagePlan(KoreStage.RELEASE_POOLS, 1.seconds),
                    StagePlan(KoreStage.DRAIN, 1.seconds),
                ),
            )
        }
    }

    @Test
    fun `a stage named twice is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ShutdownSequence(
                listOf(
                    StagePlan(KoreStage.DRAIN, 1.seconds),
                    StagePlan(KoreStage.DRAIN, 1.seconds),
                ),
            )
        }
    }

    @Test
    fun `a negative deadline is refused`() {
        assertFailsWith<IllegalArgumentException> { StagePlan(KoreStage.DRAIN, (-1).seconds) }
    }
}
