package io.github.youndie.kore.signal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import platform.posix.SIGTERM
import platform.posix.raise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A real signal, raised in this process.
 *
 * That is the point: everything about this mechanism is a claim about what happens when the operating
 * system interrupts the program, and there is no way to check it with a double. If the handler is not
 * installed, the test process dies of `SIGTERM` — which is a clear failure and a correct one.
 */
@OptIn(ExperimentalForeignApi::class)
class ShutdownSignalWatchNativeTest {
    @AfterTest
    fun clearTheFlag() {
        resetRaisedSignalForTest()
    }

    @Test
    fun `a raised SIGTERM is what awaitSignal returns`() = runTest {
        val watch = installShutdownSignalWatch(pollInterval = 1.milliseconds)

        raise(SIGTERM)

        // Real time, not the virtual clock: the poll loop is waiting on a flag a signal handler
        // writes, and there is nothing for a test scheduler to advance.
        val signal = withTimeout(5.seconds) { watch.awaitSignal() }

        assertEquals(ShutdownSignal.SIGTERM, signal)
        watch.close()
    }

    /**
     * The reason this test exists rather than a sentence citing glibc.
     *
     * `signal()` has two historical behaviours: the handler either stays installed or is reset to the
     * default disposition after it fires. With the second, a **second** `SIGTERM` — which is exactly
     * what an impatient operator or a second orchestrator sends — would terminate the process in the
     * middle of the shutdown sequence.
     *
     * If that were the behaviour here, this test would not fail. The process would die.
     */
    @Test
    fun `a second SIGTERM does not kill the process`() = runTest {
        val watch = installShutdownSignalWatch(pollInterval = 1.milliseconds)

        raise(SIGTERM)
        raise(SIGTERM)

        assertEquals(ShutdownSignal.SIGTERM, withTimeout(5.seconds) { watch.awaitSignal() })
        watch.close()
    }

    @Test
    fun `the first signal names the shutdown and a later one does not rewrite it`() = runTest {
        val watch = installShutdownSignalWatch(pollInterval = 1.milliseconds)

        raise(SIGTERM)
        raise(platform.posix.SIGINT)

        assertEquals(ShutdownSignal.SIGTERM, withTimeout(5.seconds) { watch.awaitSignal() })
        watch.close()
    }
}
