package io.github.youndie.kore.lifecycle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ShutdownPlanTest {
    @Test
    fun `a process that registered nothing still produces the whole sequence`() = runTest {
        val transcript = shutdownSequence(timeSource = testScheduler.timeSource).run()

        // Not "contains the stages that had work". A stage missing from a transcript has to be a
        // defect, and it can only be that if every stage is always there.
        assertEquals(KoreStage.specifiedOrder, transcript.order)
    }

    @Test
    fun `consumers run before pools and telemetry runs last`() = runTest {
        val order = mutableListOf<String>()
        fun recording(name: String) =
            object : ShutdownParticipant {
                override val name = name
                override suspend fun stop() {
                    order += name
                }
            }

        shutdownSequence(timeSource = testScheduler.timeSource) {
            telemetry(recording("telemetry"))
            pool(recording("pool"))
            consumer(recording("consumer"))
        }.run()

        // Registered in the wrong order on purpose: the sequence is the specification, not the order
        // somebody happened to call the builder in.
        assertEquals(listOf("consumer", "pool", "telemetry"), order)
    }

    @Test
    fun `the engine drains before any release group`() = runTest {
        val order = mutableListOf<String>()
        fun recording(name: String) =
            object : ShutdownParticipant {
                override val name = name
                override suspend fun stop() {
                    order += name
                }
            }

        shutdownSequence(timeSource = testScheduler.timeSource) {
            pool(recording("pool"))
            drain(recording("engine"))
            consumer(recording("consumer"))
        }.run()

        assertEquals(listOf("engine", "consumer", "pool"), order)
    }

    @Test
    fun `a second drain participant is refused`() {
        assertFailsWith<IllegalStateException> {
            shutdownSequence {
                drain(Takes("first", 1.seconds))
                drain(Takes("second", 1.seconds))
            }
        }
    }

    @Test
    fun `each group gets its own deadline rather than sharing one`() = runTest {
        val deadlines = ShutdownDeadlines(preDrainWait = 1.seconds, drain = 2.seconds, releaseGroup = 3.seconds)

        val transcript =
            shutdownSequence(deadlines, timeSource = testScheduler.timeSource) {
                consumer(Hangs("consumer"))
                pool(Hangs("pool"))
                telemetry(Hangs("telemetry"))
            }.run()

        // Three groups hanging, three separate deadlines spent — not one budget the first group
        // consumed entirely, which is the whole reason they are separate.
        assertEquals(3.seconds, transcript[KoreStage.RELEASE_CONSUMERS]!!.took)
        assertEquals(3.seconds, transcript[KoreStage.RELEASE_POOLS]!!.took)
        assertEquals(3.seconds, transcript[KoreStage.RELEASE_TELEMETRY]!!.took)
    }

    @Test
    fun `the announce wait is spent even with nothing registered`() = runTest {
        // The other two are shortened because with the defaults this sums to 31s against a 30s
        // grace period, and B-12's fit check refuses it. The check found that here, in this suite,
        // on its first run — which is the cheap place for a budget to be wrong.
        val transcript =
            shutdownSequence(
                ShutdownDeadlines(preDrainWait = 7.seconds, drain = 5.seconds, releaseGroup = 1.seconds),
                timeSource = testScheduler.timeSource,
            ).run()

        // The announce stage has no participants by construction, so a stage machine that skipped
        // empty stages would skip the one whose entire job is to wait. It is the stage most likely to
        // be optimised away by somebody who has not read why it is there.
        assertEquals(7.seconds, transcript[KoreStage.ANNOUNCE]!!.took)
    }

    @Test
    fun `the total is the sum a grace period has to accommodate`() {
        val deadlines = ShutdownDeadlines(preDrainWait = 5.seconds, drain = 15.seconds, releaseGroup = 3.seconds)

        assertEquals(29.seconds, deadlines.total)
        assertTrue(deadlines.total < 30.seconds, "the defaults no longer fit a default grace period")
    }

    @Test
    fun `a negative deadline is refused`() {
        assertFailsWith<IllegalArgumentException> { ShutdownDeadlines(drain = (-1).seconds) }
    }

    // --- B-12: the sequence has to fit the grace period ------------------------------------------

    @Test
    fun `a sequence longer than the grace period is refused and names both numbers`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ShutdownDeadlines(preDrainWait = 10.seconds, drain = 30.seconds, gracePeriod = 30.seconds)
            }

        // The reader has to see WHICH of the two they got wrong without opening the source.
        assertTrue(failure.message!!.contains("49s"), "the message did not name the sum: ${failure.message}")
        assertTrue(failure.message!!.contains("30s"), "the message did not name the grace period")
        assertTrue(failure.message!!.contains("terminationGracePeriodSeconds"), "it did not say what to change")
    }

    @Test
    fun `a sequence that exactly fills the grace period is allowed`() {
        // The boundary is <=, not <. A sequence that uses its whole budget has not overrun it, and
        // refusing it would make the printed sum a lie by one second.
        ShutdownDeadlines(preDrainWait = 5.seconds, drain = 16.seconds, releaseGroup = 3.seconds, gracePeriod = 30.seconds)
    }

    @Test
    fun `an undeclared grace period is assumed and the assumption is visible`() {
        val deadlines = ShutdownDeadlines()

        assertEquals(ShutdownDeadlines.KUBERNETES_DEFAULT_GRACE_PERIOD, deadlines.gracePeriod)
        assertEquals(false, deadlines.gracePeriodWasDeclared)
        // The whole point of B-25's leading hypothesis: an assumption somebody can contradict is not
        // the same thing as a constant.
        assertTrue(deadlines.describe().contains("ASSUMED"), "the assumption was not printed")
    }

    @Test
    fun `a declared grace period is used and says it was declared`() {
        val deadlines = ShutdownDeadlines(gracePeriod = 60.seconds)

        assertEquals(60.seconds, deadlines.gracePeriod)
        assertTrue(deadlines.gracePeriodWasDeclared)
        assertTrue(deadlines.describe().contains("(declared)"))
        assertTrue(!deadlines.describe().contains("ASSUMED"))
    }

    @Test
    fun `the description shows the sum beside the grace period`() {
        val text = ShutdownDeadlines(gracePeriod = 40.seconds).describe()

        assertTrue(text.contains("total"), "the sum was not shown")
        assertTrue(text.contains("29s"), "the sum was not the defaults' 29s: $text")
        assertTrue(text.contains("40s"), "the grace period was not shown")
    }

    @Test
    fun `the shipped defaults fit an undeclared grace period`() {
        // If this ever fails, every service that configured nothing stops starting — which is the
        // right failure and the wrong moment to discover it.
        val deadlines = ShutdownDeadlines()

        assertTrue(deadlines.total <= deadlines.gracePeriod, "${deadlines.total} > ${deadlines.gracePeriod}")
    }
}
