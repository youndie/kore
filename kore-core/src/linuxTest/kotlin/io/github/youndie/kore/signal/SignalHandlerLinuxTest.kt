package io.github.youndie.kore.signal

import platform.posix.SIGINT
import platform.posix.SIGTERM
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * B-64, as a property rather than a crash rate: the handler the kernel runs is kore's **C** function.
 *
 * The crash it prevents is timing (about one early `SIGTERM` in 100 on the sample, and only when the
 * signal lands on a newborn worker thread), so a test that waited for it would pass by luck. What
 * decides it is which function the kernel calls, and that is checkable exactly: a `staticCFunction`
 * put back has a different address, and this goes red. Linux only, because only Linux has the C
 * handler — see `installSignalHandlers`.
 */
class SignalHandlerLinuxTest {
    @Test
    fun `the installed handler for both signals is kore's C function`() {
        installShutdownSignalWatch(pollInterval = 1.milliseconds).close()

        assertTrue(koreHandlerIsInstalled(SIGTERM), "SIGTERM is not handled by kore's C handler")
        assertTrue(koreHandlerIsInstalled(SIGINT), "SIGINT is not handled by kore's C handler")
    }
}
