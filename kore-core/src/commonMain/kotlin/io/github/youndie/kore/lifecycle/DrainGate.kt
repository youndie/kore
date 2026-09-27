package io.github.youndie.kore.lifecycle

import kotlin.concurrent.Volatile

/**
 * Whether the **drain** has begun — the one moment a new arrival may be refused.
 *
 * A separate latch from [io.github.youndie.kore.health.ReadinessGate], because the two flip in
 * different stages and mean opposite things to a client. Readiness falls at the start of the
 * announce, and the announce exists to go on **serving** while that news travels: a request that
 * arrives then was routed here by a node that has not heard yet, and answering it is the stage's
 * whole purpose. Only the drain refuses (feature-ordered-shutdown §3).
 *
 * One flag for both was what the sample wired, and what every consumer copied from it: the refusal
 * gated on readiness, so a `503` answered every request from the first millisecond of the announce.
 * The oracle's A5 caught it only once the pre-drain wait outlasted its requests (B-61).
 *
 * `@Volatile` and one-way, for the reasons [io.github.youndie.kore.health.ReadinessGate] gives.
 */
public class DrainGate {
    @Volatile
    private var draining: Boolean = false

    /** What [io.github.youndie.kore.ktor.installShutdownRefusal] asks. */
    public val isDraining: Boolean get() = draining

    /**
     * Flips the latch. Idempotent and one-way.
     *
     * Called by the drain stage's participant as its first act, before the engine is told to stop —
     * so nothing is refused while the announce is still waiting, and nothing new is served once the
     * engine is finishing what it has.
     */
    public fun beginDrain() {
        draining = true
    }
}
