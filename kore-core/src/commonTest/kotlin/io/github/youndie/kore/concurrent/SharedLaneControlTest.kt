package io.github.youndie.kore.concurrent

import io.github.youndie.kore.health.HealthCheck
import io.github.youndie.kore.health.HealthRegistry
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.shutdownSequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The positive control for [BlockingCheckTest]: **on one thread, a blocking check does delay the
 * shutdown sequence.**
 *
 * Without it that test passes on a library that separates nothing — a green result would prove only
 * that the machine is fast. This is the failure it is watching for, produced on purpose.
 *
 * ## It no longer uses kore's own lanes, and that is the finding
 *
 * It used to put both on `KoreDispatchers.checks`, because on Kotlin/Native that was a thread kore
 * owned. It is `Dispatchers.IO` on both platforms now (research §1.14, B-42) — elastic, so sharing
 * it delays nothing and the control had nothing left to demonstrate. The single thread is created
 * here instead, which says the true thing: the danger is not kore's defaults, it is **a consumer
 * pointing a lane at a dispatcher that cannot grow**. The defaults are why that is hard to do by
 * accident; this is what it would cost.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
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
    fun `sharing one thread with a blocking check does delay the sequence`() = runTest {
        val oneThread = newSingleThreadContext("kore-control")
        val registry = HealthRegistry(listOf(BlocksItsThread()), refreshInterval = 1.milliseconds)
        val lane = CoroutineScope(oneThread + Job())
        registry.start(lane, oneThread)

        val took =
            withContext(oneThread) {
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
        lane.cancel()
        oneThread.close()
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
