package io.github.youndie.kore.signal

import io.github.youndie.kore.signal.native.kore_handler_is_installed
import io.github.youndie.kore.signal.native.kore_install_signal_handlers
import io.github.youndie.kore.signal.native.kore_raised_signal
import io.github.youndie.kore.signal.native.kore_reset_raised_signal
import kotlinx.cinterop.ExperimentalForeignApi

/** The handler is C (`koreSignal.def`): no Kotlin runs on the thread that receives the signal (B-64). */
@OptIn(ExperimentalForeignApi::class)
internal actual fun installSignalHandlers() = kore_install_signal_handlers()

@OptIn(ExperimentalForeignApi::class)
internal actual fun raisedSignal(): Int = kore_raised_signal()

@OptIn(ExperimentalForeignApi::class)
internal actual fun resetRaisedSignalForTest() = kore_reset_raised_signal()

/** Test-only: whether the handler installed for [signo] is kore's C one — the property B-64 depends on. */
@OptIn(ExperimentalForeignApi::class)
internal fun koreHandlerIsInstalled(signo: Int): Boolean = kore_handler_is_installed(signo) != 0
