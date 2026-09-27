package io.github.youndie.kore.ktor

import io.ktor.server.application.ApplicationStarted
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
 * ## On Kotlin/Native it also takes the signal, before `start` and again inside it
 *
 * `start` installs Ktor's handler, and until `runUntilSignal` installed kore's there was a window —
 * the server already answering — in which a `SIGTERM` ran Ktor's: `stop()` and `runBlocking` on the
 * signal stack, interrupting whatever the thread held. Measured on the sample with the window widened
 * to a second, **30 of 30** early signals hung the process inside that handler; unwidened, about one
 * in 200 segfaulted (B-63). So this installs kore's handler before `start`, again at
 * `ApplicationStarted` — after Ktor's, before the engine serves — and once more when `start` returns.
 * A signal from then on is recorded, and the sequence runs when `runUntilSignal` asks.
 *
 * **Which means a native service that calls this must go on to `runUntilSignal`** (or install the
 * watch itself): kore's handler only records the signal. What remains is module loading, between
 * Ktor's handler going in and `ApplicationStarted`; nothing has been served there, and it is Ktor's
 * handler that misbehaves in it (research-upstream-proposals §1.2).
 *
 * @throws IllegalStateException on the JVM when Ktor's hook was already switched on before this call —
 *   a server started earlier in the same process fixed the value. Setting
 *   `-Dio.ktor.server.engine.ShutdownHook=false` on the command line is the way out.
 */
public fun <S : EmbeddedServer<*, *>> S.startForKore(): S {
    switchOffKtorShutdownHook()
    takeTheSignalForKore()
    // AGAIN AT `ApplicationStarted`: Ktor's native `start` installs its own handler on
    // `ApplicationStarting`, and `ApplicationStarted` is raised after that and before the engine is
    // started — so from here on nothing that has been served can meet Ktor's handler (B-63).
    val retake = monitor.subscribe(ApplicationStarted) { takeTheSignalForKore() }
    try {
        start(wait = false)
    } finally {
        retake.dispose()
    }
    takeTheSignalForKore()
    return this
}

/**
 * Makes kore's handler the one a `SIGTERM` or `SIGINT` meets, from now on — before `runUntilSignal`
 * installs the watch that acts on it. A no-op on the JVM, where the watch is a shutdown hook and a
 * second one would be a second hook to release.
 */
internal expect fun takeTheSignalForKore()

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
