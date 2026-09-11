package io.github.youndie.kore.lifecycle

import io.github.youndie.kore.health.ReadinessGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class AnnounceStageTest {
    @Test
    fun `readiness is shut before the drain stage begins`() = runTest {
        val gate = ReadinessGate()
        var readyWhenTheDrainRan: Boolean? = null

        val drainWatcher =
            object : ShutdownParticipant {
                override val name = "engine"
                override suspend fun stop() {
                    readyWhenTheDrainRan = !gate.isShuttingDown
                }
            }

        shutdownSequence(timeSource = testScheduler.timeSource) {
            announce(AnnounceNotReady(gate))
            drain(drainWatcher)
        }.run()

        // The item's whole claim, asserted from inside the drain rather than after the sequence: by
        // the time anything stops accepting, the process has already stopped saying it is ready.
        assertEquals(false, readyWhenTheDrainRan, "the drain began while the process still claimed to be ready")
    }

    @Test
    fun `the wait happens after the flip rather than instead of it`() = runTest {
        val gate = ReadinessGate()

        val transcript =
            shutdownSequence(
                ShutdownDeadlines(preDrainWait = 6.seconds),
                timeSource = testScheduler.timeSource,
            ) {
                announce(AnnounceNotReady(gate))
            }.run()

        assertTrue(gate.isShuttingDown, "the announce stage did not flip the gate")
        // The flip takes microseconds and the wait is the work. A stage that did only the flip would
        // pass every assertion about readiness and defeat the purpose (research §1.10).
        assertEquals(6.seconds, transcript[KoreStage.ANNOUNCE]!!.took)
    }

    @Test
    fun `the gate is still shut when the sequence has finished`() = runTest {
        val gate = ReadinessGate()

        shutdownSequence(timeSource = testScheduler.timeSource) { announce(AnnounceNotReady(gate)) }.run()

        assertFalse(gate.verdict().ready)
    }
}
