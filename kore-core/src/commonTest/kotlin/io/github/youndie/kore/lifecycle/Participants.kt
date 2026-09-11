package io.github.youndie.kore.lifecycle

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/** Takes a known time, then optionally throws. The ordinary participant. */
internal class Takes(
    override val name: String,
    private val time: Duration,
    private val throwing: Throwable? = null,
) : ShutdownParticipant {
    var started: Boolean = false
        private set
    var finished: Boolean = false
        private set

    override suspend fun stop() {
        started = true
        delay(time)
        throwing?.let { throw it }
        finished = true
    }
}

/** Never returns, but stops when cancelled. The cooperative hang. */
internal class Hangs(override val name: String) : ShutdownParticipant {
    override suspend fun stop(): Nothing = awaitCancellation()
}

/**
 * Keeps running after it is cancelled. The uncooperative hang, and the case that decides whether the
 * time bound in [ShutdownSequence] is a promise or a hope.
 */
internal class IgnoresCancellation(
    override val name: String,
    private val time: Duration,
) : ShutdownParticipant {
    override suspend fun stop() {
        withContext(NonCancellable) { delay(time) }
    }
}
