package io.github.youndie.kore.ktor

import io.github.youndie.kore.signal.installShutdownSignalWatch

/**
 * Never on Kotlin/Native in the sense that matters: Ktor's hook there is one global slot, and kore's
 * handler replaces it — installed around `start` by `startForKore` and again by `runUntilSignal` (research §1.3, consequence 3; B-63).
 */
internal actual fun ktorShutdownHookArmed(): Boolean? = false

internal actual fun switchOffKtorShutdownHook() = Unit

/**
 * kore's handler, installed now: it only sets the flag `runUntilSignal`'s watch reads. Installing it
 * again later — which `runUntilSignal` does — replaces it with itself. The watch this returns is not
 * needed; the one that matters is `runUntilSignal`'s.
 */
internal actual fun takeTheSignalForKore() {
    installShutdownSignalWatch()
}
