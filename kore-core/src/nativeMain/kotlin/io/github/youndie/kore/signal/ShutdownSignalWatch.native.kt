package io.github.youndie.kore.signal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.delay
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.signal
import kotlin.concurrent.AtomicInt
import kotlin.time.Duration

/**
 * The flag, and the only thing the handler touches.
 *
 * A top level `val` in Kotlin/Native is initialised on first access, and initialising it from inside
 * a signal handler would allocate — which is the thing this whole design exists to avoid. So
 * [installShutdownSignalWatch] reads it once **before** installing any handler, and by the time a
 * signal can arrive there is nothing left to initialise.
 */
private val raised = AtomicInt(NONE)

private const val NONE = 0

@OptIn(ExperimentalForeignApi::class)
public actual fun installShutdownSignalWatch(
    pollInterval: Duration,
    releaseTimeout: Duration,
): ShutdownSignalWatch {
    // Forces the lazy initialisation of `raised` while it is still safe to allocate. See above.
    raised.value

    // `compareAndSet` rather than a store: the FIRST signal is the one that names the shutdown, and
    // a second arriving mid-sequence must not rewrite the answer. It is also a lock-free instruction,
    // which is what makes it safe to run here at all.
    signal(SIGTERM, staticCFunction<Int, Unit> { raised.compareAndSet(NONE, SIGTERM) })
    signal(SIGINT, staticCFunction<Int, Unit> { raised.compareAndSet(NONE, SIGINT) })

    return PosixShutdownSignalWatch(pollInterval)
}

/**
 * Polls the flag.
 *
 * Polling rather than the self-pipe trick, which is the textbook answer.
 *
 * A pipe needs a thread parked in a blocking `read` for the life of the process, and it buys at most
 * one poll interval of a thirty-second grace period. The poll costs a wakeup every [pollInterval] on
 * a dispatcher kore does not own — `KoreDispatchers.lifecycle`, which is `Dispatchers.IO` on every
 * target (research D9, corrected by B-42).
 */
private class PosixShutdownSignalWatch(private val pollInterval: Duration) : ShutdownSignalWatch {
    override suspend fun awaitSignal(): ShutdownSignal {
        while (true) {
            when (raised.value) {
                SIGTERM -> return ShutdownSignal.SIGTERM
                SIGINT -> return ShutdownSignal.SIGINT
                else -> delay(pollInterval)
            }
        }
    }

    /** Nothing to release: on Native the handler returned long ago and `main` ends the process. */
    override fun releaseProcess(): Unit = Unit

    override fun close(): Unit = Unit
}

/** Test-only, and only meaningful in-process: puts the flag back so a second case can raise again. */
internal fun resetRaisedSignalForTest() {
    raised.value = NONE
}
