package io.github.youndie.kore.concurrent

import io.github.youndie.kore.health.HealthCheck
import io.github.youndie.kore.health.HealthRegistry
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
 * The positive control for `BlockingCheckTest`: **put both on one lane and the sequence does wait.**
 *
 * Without this, that test passes on a library that separates nothing — a green result proving only
 * that the machine is fast. Here the health loop and the sequence share `KoreDispatchers.checks`,
 * which on Kotlin/Native is one thread kore owns, and the blocking check holds it.
 *
 * Native only, and that is the point rather than a limitation: on the JVM both lanes are
 * `Dispatchers.IO`, which grows a thread instead of queueing, so the control would have nothing to
 * demonstrate and would fail for a reason that is not a defect.
 */
class SharedLaneControlTest {
    private class BlocksItsThread : HealthCheck {
        override val name: String = "blocked dependency"
        override val timeout: Duration = 100.milliseconds

        override suspend fun check() {
            val until = TimeSource.Monotonic.markNow()
            @Suppress("ControlFlowWithEmptyBody")
            while (until.elapsedNow() < BLOCK) {
            }
        }
    }

    @Test
    fun `sharing one lane with a blocking check does delay the sequence`() = runTest {
        val registry = HealthRegistry(listOf(BlocksItsThread()), refreshInterval = 1.milliseconds)
        val oneLane = CoroutineScope(KoreDispatchers.checks + Job())
        registry.start(oneLane)

        val took =
            withContext(KoreDispatchers.checks) {
                val startedAt = TimeSource.Monotonic.markNow()
                shutdownSequence(
                    ShutdownDeadlines(
                        preDrainWait = 10.milliseconds,
                        drain = 50.milliseconds,
                        releaseGroup = 50.milliseconds,
                    ),
                ).run()
                startedAt.elapsedNow()
            }

        registry.stop()
        oneLane.cancel()
        assertTrue(
            took > BLOCK / 2,
            "the sequence finished in $took on a thread a check was holding for $BLOCK — " +
                "then BlockingCheckTest is not measuring the separation it claims to",
        )
    }

    private companion object {
        val BLOCK: Duration = 2.seconds
    }
}
