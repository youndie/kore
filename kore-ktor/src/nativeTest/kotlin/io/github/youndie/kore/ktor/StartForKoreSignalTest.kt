package io.github.youndie.kore.ktor

import io.github.youndie.kore.signal.ShutdownSignal
import io.github.youndie.kore.signal.installShutdownSignalWatch
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.SIGTERM
import platform.posix.raise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * B-63: from the moment `startForKore` returns, a `SIGTERM` is kore's — recorded for the watch — and
 * not Ktor's, which runs `stop()` and `runBlocking` on the signal stack.
 *
 * `raise` runs the handler synchronously on this thread, so the assertion needs no timing: whichever
 * handler is installed runs before the next line. With Ktor's, the flag kore's watch reads is never
 * set and the wait below ends empty — a red test, not a hang, because this thread holds nothing
 * Ktor's handler waits for.
 *
 * `runBlocking`, not `runTest`: the watch polls on a real clock. And one case only: the flag is
 * process-wide and nothing in `kore-ktor` can put it back, so a second case here would read this one's
 * signal.
 */
class StartForKoreSignalTest {
    @Test
    fun `a SIGTERM straight after startForKore is recorded for kore rather than run by Ktor`() {
        val server = embeddedServer(CIO, port = 0) {}.startForKore()
        try {
            raise(SIGTERM)

            val signal = runBlocking { withTimeoutOrNull(2.seconds) { installShutdownSignalWatch().awaitSignal() } }

            assertEquals(ShutdownSignal.SIGTERM, signal, "kore's watch never saw the signal: Ktor's handler took it")
        } finally {
            server.stop(0, 0)
        }
    }
}
