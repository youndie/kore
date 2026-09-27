package io.github.youndie.kore.signal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.signal
import kotlin.concurrent.AtomicInt

/**
 * **Still a Kotlin handler on macOS, and still exposed to B-64.** A `staticCFunction` is a bridge that
 * initialises the runtime on the receiving thread, and a signal landing on a worker thread in its first
 * instructions can kill it. Linux has the C handler; macOS cannot, because cinterop for an Apple target
 * is not built on the Linux host kore is released from. macOS is a development target.
 *
 * A top-level `val` is initialised on first access, and initialising it inside a signal handler would
 * allocate — so [installSignalHandlers] reads it once before installing anything.
 */
private val raised = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
internal actual fun installSignalHandlers() {
    raised.value
    signal(SIGTERM, staticCFunction<Int, Unit> { raised.compareAndSet(0, SIGTERM) })
    signal(SIGINT, staticCFunction<Int, Unit> { raised.compareAndSet(0, SIGINT) })
}

internal actual fun raisedSignal(): Int = raised.value

internal actual fun resetRaisedSignalForTest() {
    raised.value = 0
}
