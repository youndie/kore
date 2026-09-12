package io.github.youndie.kore.concurrent

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The two lanes kore's own background work runs in. **Two, and not one** — B-38.
 *
 * `Dispatchers.IO` is `internal` on Kotlin/Native at the coroutines version this repository pins
 * (1.11.0), verified by the compiler rather than by the documentation: *"Cannot access 'val IO:
 * CoroutineDispatcher': it is internal in 'kotlinx.coroutines.Dispatchers'"*. So a library that wants
 * somewhere to put work that might block has to own it, and owning it means deciding how many
 * threads a service pays for kore existing.
 *
 * ## Why two lanes rather than one
 *
 * [checks] runs dependency checks, and a dependency check **can block its thread**: it is a
 * suspending signature over a driver that may not be. `HealthRegistry.refreshOnce` bounds each check
 * with `withTimeoutOrNull`, which does not interrupt a blocking call — it only stops waiting for it,
 * while the thread stays occupied.
 *
 * [lifecycle] runs the stage machine and the signal watch. If it shared a thread with [checks], a
 * check blocked on a dead database would hold the one thread the shutdown sequence needs, and a
 * `SIGTERM` would be answered whenever the socket timed out instead of within its deadline. The
 * ordered shutdown is the specification this library exists to keep; a hang there is the failure
 * that matters most, so it gets a lane nothing else can occupy.
 *
 * ## Why not one lane per check
 *
 * A thread per dependency buys freshness for the checks that are not blocked, and costs a thread for
 * every dependency a service has. It buys nothing for shutdown, which is already protected. A check
 * stalled behind another one is reported as a *stale answer with its age*, which `HealthRegistry`
 * already does and a probe already reads — a degradation the design states rather than hides.
 *
 * ## What it costs
 *
 * **Kotlin/Native: two threads for the life of the process**, created lazily, so a binary that never
 * starts a health loop pays for one. **JVM: none of its own** — both lanes are `Dispatchers.IO`,
 * which is elastic, shared, and grows past a blocked thread rather than queueing behind it.
 *
 * A consumer that wants different numbers passes its own dispatcher; these are the defaults, not a
 * policy. What they are not is *unstated*, which is what they were before this.
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
