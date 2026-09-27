package io.github.youndie.kore.ktor

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The property itself, rather than a consequence of it: when the check returns, it holds no descriptor.
 *
 * `the engine can bind the port the moment the check returns` is the consequence, and it could not
 * tell a close that lands microseconds later on the selector thread from one that has happened — the
 * old implementation passed it 2 × 200 times on the Linux box and failed once on a CI runner. Counting
 * `/proc/self/fd` straight after the call does not wait for the selector thread, so a descriptor
 * queued for it is still there to count. Linux only: that directory is the probe.
 */
class ListenCheckDescriptorTest {
    @Test
    fun `the check holds no descriptor once it returns`() {
        repeat(ATTEMPTS) { attempt ->
            val port = freePort()
            val before = openDescriptors()
            listenProblem("0.0.0.0", port)
            val after = openDescriptors()

            assertEquals(before, after, "attempt $attempt: descriptors open before the check and after it")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun openDescriptors(): Int {
        val directory = opendir("/proc/self/fd") ?: error("cannot open /proc/self/fd")
        var count = 0
        try {
            while (readdir(directory) != null) count++
        } finally {
            closedir(directory)
        }
        return count
    }

    private companion object {
        const val ATTEMPTS = 20
    }
}
