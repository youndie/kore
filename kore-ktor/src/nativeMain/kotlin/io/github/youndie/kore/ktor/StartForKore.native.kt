package io.github.youndie.kore.ktor

/**
 * Never on Kotlin/Native in the sense that matters: Ktor's hook there is one global slot, and kore's
 * handler — installed after the server is serving — replaces it (research §1.3, consequence 3).
 */
internal actual fun ktorShutdownHookArmed(): Boolean? = false

internal actual fun switchOffKtorShutdownHook() = Unit
