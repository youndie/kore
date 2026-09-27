package io.github.youndie.kore.ktor

import io.ktor.server.engine.EmbeddedServer

/**
 * Starts the server the way kore's shutdown needs it started: **Ktor's own shutdown hook off**, and
 * `wait = false`. Call it where `start(wait = false)` would have gone.
 *
 * ## Why this is not `start`
 *
 * `EmbeddedServer.start` registers `addShutdownHook { stop() }` on every target, unconditionally, and
 * the two targets make that mean different things (research §1.3):
 *
 * * **on Kotlin/Native** the hook is one global slot, and kore's handler, installed after the server
 *   is serving, takes it. Nothing to switch off;
 * * **on the JVM** it is an independent `Runtime` shutdown hook, and the JVM runs every hook
 *   **concurrently**. Ktor's calls `stop()` the moment `SIGTERM` lands, while kore's sequence is still
 *   announcing: the listener is gone, readiness is a refused connection instead of a `503`, and the
 *   announce stage is spent with nobody able to hear it. Measured on kore's own JVM sample: 48 of 48
 *   new connections refused inside a five-second announce, the first 13 ms after the signal (#90).
 *
 * Ktor's JVM hook can be switched off by the system property `io.ktor.server.engine.ShutdownHook`, and
 * only **before the first `start()` in the process**: Ktor reads it into a top-level `val`, on the
 * first line of `start()`. A module, a plugin or [installKoreProbes] all run after that line, which is
 * why the switch lives here rather than in something that is already called. kore loses nothing by
 * it — [EngineDrain] stops the engine itself, in the drain stage.
 *
 * `wait` is not a parameter because `true` is never right under kore: the main thread has to be free
 * to wait for the signal and run the sequence.
 *
 * @throws IllegalStateException on the JVM when Ktor's hook was already switched on before this call —
 *   a server started earlier in the same process fixed the value. Setting
 *   `-Dio.ktor.server.engine.ShutdownHook=false` on the command line is the way out.
 */
public fun <S : EmbeddedServer<*, *>> S.startForKore(): S {
    switchOffKtorShutdownHook()
    start(wait = false)
    return this
}

/** Ktor's switch for its JVM shutdown hook, read once per process. */
internal const val KTOR_SHUTDOWN_HOOK_PROPERTY: String = "io.ktor.server.engine.ShutdownHook"

/**
 * Whether a server started in this process installs Ktor's own shutdown hook beside kore's.
 *
 * `true` means the announce will not be heard: see [startForKore]. `null` means this target cannot
 * tell — the value lives in a Ktor internal, and a Ktor that moved it answers "unknown" here rather
 * than "fine".
 */
internal expect fun ktorShutdownHookArmed(): Boolean?

/** Sets the switch, and fails if it was read before it could be set. */
internal expect fun switchOffKtorShutdownHook()

internal fun ktorShutdownHookMessage(): String =
    "Ktor's own JVM shutdown hook is on, so on SIGTERM it stops the engine concurrently with kore's " +
        "announce and readiness is a refused connection instead of a 503 (kore#90). Start the server " +
        "with startForKore() instead of start(), or run the JVM with " +
        "-D$KTOR_SHUTDOWN_HOOK_PROPERTY=false. The value is fixed by the first start() in the process, " +
        "so it has to be switched off before any server has started."
