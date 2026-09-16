package io.github.youndie.kore.concurrent

import io.github.youndie.kore.health.HealthCheck
import io.github.youndie.kore.health.HealthRegistry
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.shutdownSequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A dependency check that blocks its thread must not delay the shutdown sequence — B-38's acceptance.
 *
 * **Real time, real threads, no virtual clock.** The thing under test is which thread runs what, and
 * a test dispatcher would run both on the same one and answer a different question. The durations
 * are therefore wall-clock and kept small; the assertion is a ratio between them rather than an
 * absolute, so a slow machine does not turn it red.
 */
class BlockingCheckTest {
    /**
     * Occupies its thread without suspending — the shape of a driver that is blocking underneath a
     * suspending signature. `Thread.sleep` has no common equivalent, and a spin is closer to the real
     * thing anyway: it cannot be interrupted by cancellation either.
     */
    private class BlocksItsThread(
        override val name: String = "blocked dependency",
        override val timeout: Duration = 100.milliseconds,
        private val forHowLong: Duration = BLOCK,
    ) : HealthCheck {
        override suspend fun check() {
            val until = TimeSource.Monotonic.markNow()
            @Suppress("ControlFlowWithEmptyBody")
            while (until.elapsedNow() < forHowLong) {
                // Deliberately empty: a suspension point here would make it a cooperative wait, which
                // is the case that was never in doubt.
            }
        }
    }

    private object Instant : ShutdownParticipant {
        override val name: String = "instant"

        override suspend fun stop() = Unit
    }

    /** The sequence with every deadline small, so the whole run is far shorter than [BLOCK]. */
    private fun sequence() =
        shutdownSequence(
            ShutdownDeadlines(
                preDrainWait = 10.milliseconds,
                drain = 50.milliseconds,
                releaseGroup = 50.milliseconds,
            ),
        ) { pool(Instant) }

    @Test
    fun `a check blocking its thread does not delay the shutdown sequence`() = runTest {
        val registry = HealthRegistry(listOf(BlocksItsThread()), refreshInterval = 1.milliseconds)
        val checks = CoroutineScope(KoreDispatchers.checks + Job())
        registry.start(checks)

        val took =
            withContext(KoreDispatchers.lifecycle) {
                val startedAt = TimeSource.Monotonic.markNow()
                sequence().run()
                startedAt.elapsedNow()
            }

        // `stop`, not `stopAndJoin`: this suite measures a check holding a thread, so waiting for it
        // is the thing being measured rather than the thing being tidied up.
        @Suppress("DEPRECATION")
        registry.stop()
        checks.cancel()
        assertTrue(
            took < BLOCK / 2,
            "the shutdown sequence took $took while a check held a thread for $BLOCK — " +
                "the lanes are not separate",
        )
    }

    internal companion object {
        /**
         * Long enough that a sequence waiting behind it cannot pass the assertion by luck, short
         * enough to sit in a unit test. The positive control that proves the assertion can fail is
         * `SharedLaneControlTest`, on Kotlin/Native where the lanes are threads kore owns.
         */
        val BLOCK: Duration = 2.seconds
    }
}
