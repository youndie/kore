package io.github.youndie.kore.signal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What asked the process to stop. */
public enum class ShutdownSignal {
    SIGTERM,
    SIGINT,

    /** The JVM is shutting down. There is no signal number here — the runtime is the messenger. */
    JVM_SHUTDOWN,
}

/**
 * Why kore installs its own handler instead of calling `EmbeddedServer.addShutdownHook`.
 *
 * On Kotlin/Native that function stores its callback in **one** file-level `AtomicReference`, so a
 * second caller silently replaces the first — including `EmbeddedServer.start`'s own hook, which is
 * registered by `start()` and cannot be removed. Whose block survives is decided by registration
 * order, which nobody writes down. Ktor's own KDoc states it (research §1.3).
 *
 * And the block it stores runs **on the signal-handler stack**, through a `staticCFunction` installed
 * with ANSI `signal()`. Ktor's does `runBlocking` there. Allocation, locks and the coroutine
 * machinery are all reachable from it and none of them is async-signal-safe.
 *
 * So: kore's handler does the smallest thing a handler may do — it writes a flag — and an ordinary
 * coroutine on an ordinary thread notices and runs the sequence.
 *
 * It is installed with `signal()` rather than `sigaction()`, and that is a portability decision
 * rather than the interesting one: `struct sigaction` has a different shape on Linux and on Darwin,
 * and all three native targets share this source set. What separates kore from Ktor here is not the
 * installer, it is what the handler does. glibc's `signal()` keeps the handler installed after it
 * fires — which matters, because with the other semantics a second `SIGTERM` would reach the default
 * disposition and kill the process mid-sequence. That is asserted rather than assumed:
 * `ShutdownSignalWatchNativeTest` raises `SIGTERM` twice.
 *
 * ## The one thing the two platforms cannot share
 *
 * On Native the handler returns and `main` carries on, so the sequence runs on the main thread and
 * the process ends when `main` returns. On the JVM the hook thread **is** the shutdown: return from
 * it and the runtime exits, halfway through whatever kore was doing. So the JVM implementation makes
 * the hook thread wait for [releaseProcess], and that asymmetry is the whole reason this is an
 * `expect`/`actual` pair rather than one implementation.
 */
public interface ShutdownSignalWatch : AutoCloseable {
    /**
     * Suspends until the process is asked to stop, and answers with what asked.
     *
     * A second signal arriving while the first is being handled changes nothing: this returns once.
     * Two `SIGTERM`s a millisecond apart is the ordinary case, not the exotic one.
     */
    public suspend fun awaitSignal(): ShutdownSignal

    /**
     * Says the shutdown sequence has finished and the process may end.
     *
     * On the JVM this is what lets the shutdown hook return. **Not calling it means the runtime waits
     * for the bounded timeout and then exits anyway** — so forgetting it costs latency, not
     * correctness, which is the right way round for a call somebody will forget.
     *
     * On Native it does nothing: `main` returning is what ends the process.
     */
    public fun releaseProcess()
}

/**
 * Installs the handler. Call it **after** the server is running.
 *
 * After, because on Native `EmbeddedServer.start` installs a hook of its own into the single slot,
 * and the last registration wins. kore wants to be last. That is an ordering dependency on somebody
 * else's code, so it is Risk 2 of the research rather than a solved problem, and the oracle asserting
 * the *sequence* rather than the exit code is what would notice if it changed.
 *
 * @param pollInterval how often the coroutine looks at the flag the handler writes. It bounds the
 *   latency between the signal and the sequence starting, and 20 ms of a 30-second grace period is
 *   not worth a self-pipe and a dedicated thread — which on Kotlin/Native is
 *   `newSingleThreadContext`, and an open question of its own (B-38).
 * @param releaseTimeout how long the JVM's shutdown hook waits for [releaseProcess] before letting
 *   the runtime go. Ignored on Native.
 */
public expect fun installShutdownSignalWatch(
    pollInterval: Duration = 20.milliseconds,
    releaseTimeout: Duration = 60.seconds,
): ShutdownSignalWatch
