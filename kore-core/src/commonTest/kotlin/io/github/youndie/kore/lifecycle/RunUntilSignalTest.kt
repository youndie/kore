package io.github.youndie.kore.lifecycle

import io.github.youndie.kore.signal.ShutdownSignal
import io.github.youndie.kore.signal.ShutdownSignalWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * A watch the test drives, recording the order it was used in.
 *
 * The order is the whole content of `runUntilSignal`: await, then run, then release, then close. A
 * consumer writing those four by hand is what the call exists to stop, so a test that only checked
 * "the sequence ran" would not be checking the thing.
 */
private class ScriptedWatch(private val signal: ShutdownSignal = ShutdownSignal.SIGTERM) : ShutdownSignalWatch {
    val calls = mutableListOf<String>()
    val awaited = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun awaitSignal(): ShutdownSignal {
        calls += "await"
        awaited.complete(Unit)
        release.await()
        return signal
    }

    override fun releaseProcess() {
        calls += "release"
    }

    override fun close() {
        calls += "close"
    }
}

private class Records(override val name: String, private val into: MutableList<String>) : ShutdownParticipant {
    override suspend fun stop() {
        into += name
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class RunUntilSignalTest {
    private fun deadlines() =
        ShutdownDeadlines(preDrainWait = 1.milliseconds, drain = 1.milliseconds, releaseGroup = 1.milliseconds)

    /**
     * The ordering #59 is about. On the JVM `releaseProcess` lets the shutdown hook return and the
     * runtime terminate, so a consumer's own code has exactly one safe place to run: inside, before
     * the release. A test that only asserted "onFinished was called" would pass against the
     * placement that loses the race.
     */
    @Test
    fun `onFinished runs before the process is released`() = runTest {
        val watch = ScriptedWatch()
        val stopped = mutableListOf<String>()

        kotlinx.coroutines.coroutineScope {
            val running =
                async {
                    runUntilSignal(
                        deadlines(),
                        watch,
                        onFinished = { watch.calls += "onFinished(${it.transcript.stages.size} stages)" },
                    ) { pool(Records("pool", stopped)) }
                }
            watch.awaited.await()
            watch.release.complete(Unit)
            running.await()
        }

        assertEquals("await", watch.calls[0])
        assertTrue(watch.calls[1].startsWith("onFinished("), "onFinished did not run before the release: ${watch.calls}")
        assertTrue(watch.calls[1].contains(" stages)"), "onFinished was handed no transcript: ${watch.calls}")
        assertEquals(listOf("release", "close"), watch.calls.drop(2))
    }

    /**
     * A callback that throws must not be able to hold a process that has been asked to stop — and
     * must not vanish either, because it failed in the one place nobody is watching.
     */
    @Test
    fun `a failing onFinished still releases the process and is then reported`() = runTest {
        val watch = ScriptedWatch()

        kotlinx.coroutines.coroutineScope {
            val running =
                async {
                    assertFailsWith<IllegalStateException> {
                        runUntilSignal(deadlines(), watch, onFinished = { error("the callback failed") }) {}
                    }
                }
            watch.awaited.await()
            watch.release.complete(Unit)
            running.await()
        }

        assertEquals(listOf("await", "release", "close"), watch.calls)
    }

    @Test
    fun `the sequence runs only after the signal arrives`() = runTest {
        val watch = ScriptedWatch()
        val stopped = mutableListOf<String>()

        kotlinx.coroutines.coroutineScope {
            val running = async { runUntilSignal(deadlines(), watch) { pool(Records("pool", stopped)) } }
            watch.awaited.await()

            assertTrue(stopped.isEmpty(), "a participant ran before the signal arrived")

            watch.release.complete(Unit)
            running.await()
        }
        assertEquals(listOf("pool"), stopped)
    }

    @Test
    fun `the process is released after the sequence and the watch is closed`() = runTest {
        val watch = ScriptedWatch()
        watch.release.complete(Unit)

        runUntilSignal(deadlines(), watch) { pool(Records("pool", mutableListOf())) }

        assertEquals(listOf("await", "release", "close"), watch.calls)
    }

    @Test
    fun `the signal and the transcript both come back`() = runTest {
        val watch = ScriptedWatch(ShutdownSignal.SIGINT)
        watch.release.complete(Unit)

        val run = runUntilSignal(deadlines(), watch) {}

        assertEquals(ShutdownSignal.SIGINT, run.signal)
        assertEquals(KoreStage.specifiedOrder, run.transcript.stages.map { it.stage })
    }

    /**
     * A stage that overruns is recorded, not thrown — so the release must still happen. Releasing
     * before the sequence would race the runtime against kore's own stages; not releasing at all
     * costs latency nobody attributes to a missing call.
     */
    @Test
    fun `a participant that ignores its deadline does not stop the release`() = runTest {
        val watch = ScriptedWatch()
        watch.release.complete(Unit)

        val run =
            runUntilSignal(deadlines(), watch) {
                pool(object : ShutdownParticipant {
                    override val name = "stuck"

                    override suspend fun stop(): Nothing = kotlinx.coroutines.awaitCancellation()
                })
            }

        assertEquals(listOf("await", "release", "close"), watch.calls)
        assertEquals(
            StageOutcome.DEADLINE_EXCEEDED,
            run.transcript.stages.single { it.stage == KoreStage.RELEASE_POOLS }.outcome,
        )
    }

    /** A plan that cannot fit is refused before anything waits for a signal. */
    @Test
    fun `an impossible plan is refused rather than awaited`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            ShutdownDeadlines(drain = 1.milliseconds * 60_000_000)
        }
    }
}
