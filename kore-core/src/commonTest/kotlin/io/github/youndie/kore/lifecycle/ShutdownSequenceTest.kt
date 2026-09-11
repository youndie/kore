package io.github.youndie.kore.lifecycle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * One concrete case per property of `docs/research/research-oracle.md` §3.2.
 *
 * These are **not** the property test — that is B-13, over generated durations and failures. What
 * they establish is B-04's acceptance criterion: that the transcript records enough to *decide* each
 * property. A property nothing here can assert is a property the generative test could not assert
 * either, and this is the cheap place to find that out.
 *
 * Every test runs on a virtual clock. A test that waits three real seconds for a three-second
 * deadline measures the runner and costs three seconds doing it.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ShutdownSequenceTest {
    private fun plan(vararg stages: StagePlan) = stages.toList()

    @Test
    fun `property 1 - the transcript is a prefix of the specified order`() = runTest {
        val sequence =
            ShutdownSequence(
                plan(
                    StagePlan(KoreStage.SIGNAL, 1.seconds),
                    StagePlan(KoreStage.ANNOUNCE, 1.seconds, listOf(Takes("readiness", 1.seconds))),
                    StagePlan(KoreStage.DRAIN, 5.seconds, listOf(Takes("engine", 2.seconds))),
                    StagePlan(KoreStage.EXIT, 1.seconds),
                ),
                testScheduler.timeSource,
            )

        val order = sequence.run().order

        assertEquals(
            KoreStage.specifiedOrder.filter { it in order.toSet() },
            order,
            "the stages ran out of specification order",
        )
        assertEquals(order.distinct(), order, "a stage ran twice")
    }

    @Test
    fun `property 2 - no stage begins before the previous one has ended`() = runTest {
        val sequence =
            ShutdownSequence(
                plan(
                    StagePlan(KoreStage.ANNOUNCE, 10.seconds, listOf(Takes("readiness", 3.seconds))),
                    StagePlan(KoreStage.DRAIN, 10.seconds, listOf(Takes("engine", 4.seconds))),
                    StagePlan(KoreStage.RELEASE_POOLS, 10.seconds, listOf(Takes("pool", 2.seconds))),
                ),
                testScheduler.timeSource,
            )

        val stages = sequence.run().stages

        stages.zipWithNext { earlier, later ->
            assertTrue(
                later.enteredAt >= earlier.leftAt,
                "${later.stage} began at ${later.enteredAt}, before ${earlier.stage} ended at ${earlier.leftAt}",
            )
        }
    }

    @Test
    fun `property 3 - a participant over its deadline does not extend the stage`() = runTest {
        val pool = Takes("pool", 1.seconds)
        val sequence =
            ShutdownSequence(
                plan(
                    StagePlan(KoreStage.RELEASE_CONSUMERS, 3.seconds, listOf(Hangs("consumer"))),
                    StagePlan(KoreStage.RELEASE_POOLS, 5.seconds, listOf(pool)),
                ),
                testScheduler.timeSource,
            )

        val transcript = sequence.run()
        val consumers = assertNotNull(transcript[KoreStage.RELEASE_CONSUMERS])

        assertEquals(StageOutcome.DEADLINE_EXCEEDED, consumers.outcome)
        assertEquals(3.seconds, consumers.took, "the stage ran past its own deadline")
        assertTrue(pool.finished, "the next stage did not run after a stage timed out")
    }

    @Test
    fun `property 4 - a participant that throws does not stop the sequence`() = runTest {
        val telemetry = Takes("tracy", 1.seconds)
        val sequence =
            ShutdownSequence(
                plan(
                    StagePlan(
                        KoreStage.RELEASE_POOLS,
                        5.seconds,
                        listOf(Takes("pool", 1.seconds, throwing = IllegalStateException("already closed"))),
                    ),
                    StagePlan(KoreStage.RELEASE_TELEMETRY, 5.seconds, listOf(telemetry)),
                ),
                testScheduler.timeSource,
            )

        val transcript = sequence.run()
        val pools = assertNotNull(transcript[KoreStage.RELEASE_POOLS])

        assertEquals(StageOutcome.COMPLETED, pools.outcome, "a throwing participant is not a timeout")
        assertEquals(listOf("pool"), pools.failures.map { it.participant })
        assertTrue(telemetry.finished, "the sequence stopped at a failing participant")
    }

    @Test
    fun `a stage records every failure and not only the first`() = runTest {
        val sequence =
            ShutdownSequence(
                plan(
                    StagePlan(
                        KoreStage.RELEASE_CONSUMERS,
                        5.seconds,
                        listOf(
                            Takes("a", 1.seconds, throwing = IllegalStateException("a")),
                            Takes("b", 1.seconds, throwing = IllegalStateException("b")),
                            Takes("c", 1.seconds),
                        ),
                    ),
                ),
                testScheduler.timeSource,
            )

        val failures = sequence.run().failures

        assertEquals(setOf("a", "b"), failures.map { it.participant }.toSet())
    }

    @Test
    fun `property 5 - the total time is bounded by the sum of the deadlines`() = runTest {
        val deadlines = listOf(2.seconds, 3.seconds, 4.seconds)
        val sequence =
            ShutdownSequence(
                plan(
                    StagePlan(KoreStage.ANNOUNCE, deadlines[0], listOf(Hangs("readiness"))),
                    StagePlan(KoreStage.DRAIN, deadlines[1], listOf(Hangs("engine"))),
                    StagePlan(KoreStage.RELEASE_POOLS, deadlines[2], listOf(Hangs("pool"))),
                ),
                testScheduler.timeSource,
            )

        val transcript = sequence.run()

        assertEquals(deadlines.reduce { a, b -> a + b }, transcript.took)
    }

    @Test
    fun `property 5 holds even when a participant ignores cancellation`() = runTest {
        val pool = Takes("pool", 1.seconds)
        val sequence =
            ShutdownSequence(
                plan(
                    StagePlan(KoreStage.RELEASE_CONSUMERS, 2.seconds, listOf(IgnoresCancellation("stuck", 30.seconds))),
                    StagePlan(KoreStage.RELEASE_POOLS, 5.seconds, listOf(pool)),
                ),
                testScheduler.timeSource,
            )

        val transcript = sequence.run()

        // The sequence refuses to WAIT for it; it cannot stop it. That is the honest half of the
        // bound, and it is why the machine detaches the stage's scope instead of joining it.
        assertEquals(2.seconds, assertNotNull(transcript[KoreStage.RELEASE_CONSUMERS]).took)
        assertTrue(pool.finished, "the sequence waited for a participant that ignores cancellation")
    }

    @Test
    fun `property 6 - concurrent triggers run the sequence once`() = runTest {
        val pool = Takes("pool", 1.seconds)
        val sequence =
            ShutdownSequence(
                plan(
                    StagePlan(KoreStage.SIGNAL, 1.seconds),
                    StagePlan(KoreStage.RELEASE_POOLS, 5.seconds, listOf(pool)),
                    StagePlan(KoreStage.EXIT, 1.seconds),
                ),
                testScheduler.timeSource,
            )

        val transcripts =
            listOf(
                async { sequence.run() },
                async { sequence.run() },
                async { sequence.run() },
            ).awaitAll()

        assertSame(transcripts[0], transcripts[1], "a second trigger ran the sequence again")
        assertSame(transcripts[0], transcripts[2], "a third trigger ran the sequence again")
        assertEquals(
            listOf(KoreStage.SIGNAL, KoreStage.RELEASE_POOLS, KoreStage.EXIT),
            transcripts[0].order,
        )
    }
}
