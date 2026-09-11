package io.github.youndie.kore.health

import kotlin.concurrent.Volatile

/**
 * Has the process finished coming up?
 *
 * **A latch, not a status.** Once it has answered yes it never answers no again — not when a
 * dependency fails, not during a shutdown. Kubernetes runs the startup probe *only* at startup
 * (research §1.10), so a later `503` here is a value nothing reads and everything misreads; and a
 * startup probe that could fail later would restart a pod that is trying to stop cleanly, which is
 * the opposite of the point.
 *
 * The gates it waits on are the service's: migrations applied, a cache warmed, a first poll done.
 * kore does not invent them — it refuses to say "started" until the service says so.
 */
public class StartupGate(
    /** Named so a `503` can say what is outstanding rather than only that something is. */
    gates: Set<String> = emptySet(),
) {
    private val outstanding = gates.toMutableSet()

    @Volatile
    private var started: Boolean = gates.isEmpty()

    public val hasStarted: Boolean get() = started

    /** What is still keeping the process out of service. Empty once [hasStarted]. */
    public val pending: Set<String> get() = if (started) emptySet() else outstanding.toSet()

    /** Says one gate has completed. Unknown names are ignored: a gate reported twice is not an error. */
    public fun completed(gate: String) {
        // An early-out, NOT a correctness guard — and that was established by mutation rather than
        // assumed. `started` is monotonic, so removing this line changes nothing observable: the
        // mutant survives and is equivalent. It stays because it says what the function is for.
        if (started) return
        outstanding -= gate
        if (outstanding.isEmpty()) started = true
    }

    /** For a service with no gates worth naming: it is up when it says it is. */
    public fun markStarted() {
        outstanding.clear()
        started = true
    }
}
