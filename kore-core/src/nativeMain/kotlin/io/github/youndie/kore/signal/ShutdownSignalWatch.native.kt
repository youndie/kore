package io.github.youndie.kore.signal

import kotlinx.cinterop.ExperimentalForeignApi
import io.github.youndie.kore.signal.native.kore_handler_is_installed
import io.github.youndie.kore.signal.native.kore_install_signal_handlers
import io.github.youndie.kore.signal.native.kore_raised_signal
import io.github.youndie.kore.signal.native.kore_reset_raised_signal
import kotlinx.coroutines.delay
import platform.posix.SIGINT
import platform.posix.SIGTERM
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class)
public actual fun installShutdownSignalWatch(
    pollInterval: Duration,
    releaseTimeout: Duration,
): ShutdownSignalWatch {
    // THE HANDLER IS C (`koreSignal.def`), and that is the whole of B-64. It used to be a
    // `staticCFunction`, whose body did nothing but a compare-and-set — and whose *bridge* initialised
    // the Kotlin/Native runtime on whichever thread the kernel picked. When that was a worker thread in
    // its first instructions, the runtime came up there before `workerRoutine` could bring it up itself,
    // and the worker died on a null memory state: 18 crashes out of 18 had the receiving thread as the
    // crashing one. A C handler runs no Kotlin, so no thread is ever initialised by a signal.
    //
    // First signal wins, as before: the C side does a lock-free compare-and-set.
    kore_install_signal_handlers()

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
@OptIn(ExperimentalForeignApi::class)
private class PosixShutdownSignalWatch(private val pollInterval: Duration) : ShutdownSignalWatch {
    override suspend fun awaitSignal(): ShutdownSignal {
        while (true) {
            when (kore_raised_signal()) {
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
@OptIn(ExperimentalForeignApi::class)
internal fun resetRaisedSignalForTest() {
    kore_reset_raised_signal()
}

/** Test-only: whether the handler installed for [signo] is kore's C one — the property B-64 depends on. */
@OptIn(ExperimentalForeignApi::class)
internal fun koreHandlerIsInstalled(signo: Int): Boolean = kore_handler_is_installed(signo) != 0
