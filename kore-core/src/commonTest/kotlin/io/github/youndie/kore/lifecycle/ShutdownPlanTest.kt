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
        val transcript = shutdownSequence(ShutdownDeadlines(preDrainWait = 7.seconds), timeSource = testScheduler.timeSource).run()

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
}
