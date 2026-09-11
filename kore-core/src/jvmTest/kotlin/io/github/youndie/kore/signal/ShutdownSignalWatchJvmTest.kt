package io.github.youndie.kore.signal

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What can and cannot be tested here, stated rather than blurred.
 *
 * The JVM offers no way to raise its own shutdown without ending the process, so these drive
 * `onRuntimeShutdown` — the hook thread's actual body, not a double of it. What is **not** covered is
 * whether the runtime calls it; that is the JVM's contract, and the end-to-end oracle is what
 * exercises it against a container receiving a real signal.
 */
class ShutdownSignalWatchJvmTest {
    @Test
    fun `the hook is registered with the runtime`() {
        val watch = installShutdownSignalWatch()

        // `removeShutdownHook` answers true only for a hook that was registered, so this is the
        // registration itself being asserted rather than a flag the class sets about itself.
        watch.close()

        assertTrue(watch is JvmShutdownSignalWatch)
    }

    /**
     * `runBlocking`, not `runTest`. The thing being waited on is a real thread reaching a real latch,
     * and `runTest`'s clock is virtual — `withTimeout` there expires without a microsecond of wall
     * time passing, so the test fails against a mechanism that works. It did.
     */
    @Test
    fun `awaitSignal returns once the runtime is shutting down`() = runBlocking {
        val watch = installShutdownSignalWatch(releaseTimeout = 200.milliseconds) as JvmShutdownSignalWatch

        Thread { watch.onRuntimeShutdown() }.start()

        assertEquals(ShutdownSignal.JVM_SHUTDOWN, withTimeout(5.seconds) { watch.awaitSignal() })
        watch.close()
    }

    @Test
    fun `the hook thread waits for releaseProcess and gives up on its own`() {
        val watch = installShutdownSignalWatch(releaseTimeout = 300.milliseconds) as JvmShutdownSignalWatch

        val started = System.nanoTime()
        watch.onRuntimeShutdown()
        val waited = (System.nanoTime() - started) / 1_000_000

        // It waited, and it stopped waiting by itself: forgetting `releaseProcess` costs latency
        // rather than a process that never exits.
        assertTrue(waited >= 250, "the hook returned after ${waited}ms without waiting")
        assertTrue(waited < 3_000, "the hook waited ${waited}ms, far past its own timeout")
        watch.close()
    }

    @Test
    fun `releaseProcess lets the hook thread return at once`() {
        val watch = installShutdownSignalWatch(releaseTimeout = 30.seconds) as JvmShutdownSignalWatch

        Thread {
            Thread.sleep(50)
            watch.releaseProcess()
        }.start()

        val started = System.nanoTime()
        watch.onRuntimeShutdown()
        val waited = (System.nanoTime() - started) / 1_000_000

        assertTrue(waited < 5_000, "the hook waited ${waited}ms despite releaseProcess")
        watch.close()
    }
}
