package io.github.youndie.kore.concurrent

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The two lanes kore's own background work runs in.
 *
 * [checks] runs dependency checks; [lifecycle] runs the stage machine and the signal watch. Both are
 * `Dispatchers.IO` on every target kore builds for, and **kore owns no threads of its own**.
 *
 * ## Why two names for one dispatcher
 *
 * Because the reason they are separable outlives the fact that they are currently equal. A
 * dependency check **can block its thread**: it is a suspending signature over a driver that may not
 * be, and `HealthRegistry.refreshOnce` bounds each check with `withTimeoutOrNull`, which stops
 * *waiting* for a blocking call without freeing the thread it holds. If the shutdown sequence shared
 * a thread with a check blocked on a dead database, `SIGTERM` would be answered whenever the socket
 * timed out instead of within its deadline — the one hang this library exists to prevent.
 *
 * `Dispatchers.IO` removes that by being elastic, so today the separation costs nothing. The names
 * remain because a consumer can point either lane somewhere else, and pointing [checks] at a
 * dispatcher that cannot grow is the way to reintroduce the hang. `SharedLaneControlTest` is what
 * that costs, measured.
 *
 * ## What this used to say
 *
 * That `Dispatchers.IO` is `internal` on Kotlin/Native and kore therefore had to own a thread per
 * lane. It is not: it is an extension property needing `import kotlinx.coroutines.IO`, and without
 * that import the compiler resolves the internal member of the same name and says "it is internal"
 * — research §1.14, [B-42]. It is elastic there too, measured rather than assumed: with 128 threads
 * blocked for three seconds, a trivial task was scheduled in 107 µs on `linuxX64`.
 */
public object KoreDispatchers {
    /** The stage machine and the signal watch. Nothing that may block belongs here. */
    public val lifecycle: CoroutineDispatcher get() = koreLifecycleDispatcher

    /** Dependency checks, which may block despite their suspending signature. */
    public val checks: CoroutineDispatcher get() = koreChecksDispatcher
}

// Top-level `expect val`s behind a plain object rather than an `expect object`, which is still a Beta
// language feature and warns on every compilation. The public shape is the object; what varies by
// platform is two values.
internal expect val koreLifecycleDispatcher: CoroutineDispatcher

internal expect val koreChecksDispatcher: CoroutineDispatcher
