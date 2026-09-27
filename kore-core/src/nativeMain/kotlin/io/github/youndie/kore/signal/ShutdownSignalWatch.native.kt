package io.github.youndie.kore.signal

import kotlinx.coroutines.delay
import platform.posix.SIGINT
import platform.posix.SIGTERM
import kotlin.time.Duration

public actual fun installShutdownSignalWatch(
    pollInterval: Duration,
    releaseTimeout: Duration,
): ShutdownSignalWatch {
    installSignalHandlers()
    return PosixShutdownSignalWatch(pollInterval)
}

/**
 * Installs the `SIGTERM` and `SIGINT` handler, which records the first signal and does nothing else.
 *
 * **Per platform, because the language of the handler is the fix (B-64).** A handler written in Kotlin
 * is a `staticCFunction`, a C-to-Kotlin bridge that initialises the runtime on whichever thread the
 * kernel picks. When that was a worker thread in its first instructions, the runtime came up there
 * before `workerRoutine` could bring it up, and the worker died on a null memory state: in 18 crashes
 * out of 18 the receiving thread was the crashing one. On Linux the handler is C (`koreSignal.def`).
 * On macOS it is still Kotlin, because cinterop for an Apple target cannot be built on the Linux host
 * kore is released from: macOS is a development target, and the defect stays there, named.
 */
internal expect fun installSignalHandlers()

/** The first signal recorded, or `0`. */
internal expect fun raisedSignal(): Int

/** Test-only: puts the flag back so a second case can raise again. */
internal expect fun resetRaisedSignalForTest()

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
            when (raisedSignal()) {
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

