package io.github.youndie.kore.health

import kotlin.concurrent.Volatile

/**
 * Is this process wedged?
 *
 * **It reads no dependency, ever.** A liveness probe that reads the database restarts a healthy pod
 * during a database blip — and then the next one, and the next — which is how a dependency outage
 * becomes an outage of everything in front of it.
 *
 * So the only thing that can fail it is a service declaring itself unrecoverable: a deadlock it
 * detected, a thread pool it cannot drain, state it cannot repair. **A restart has to be the fix**,
 * or the correct answer is a failing readiness rather than a failing liveness.
 *
 * This exists because `docs/api/endpoint-kore-admin.md` promises "`503` only for a condition a
 * restart would fix", and a promise with no mechanism behind it is a sentence. Nothing in kore calls
 * it; everything that does is the service.
 */
public class LivenessGate {
    @Volatile
    private var wedged: String? = null

    public val wedgedReason: String? get() = wedged

    public val isAlive: Boolean get() = wedged == null

    /** One-way. A process that has declared itself unrecoverable does not talk itself round. */
    public fun declareWedged(reason: String) {
        if (wedged == null) wedged = reason
    }
}
