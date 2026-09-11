package io.github.youndie.kore.health

import kotlin.concurrent.Volatile

/** Why the process is or is not willing to be sent traffic. */
public class ReadinessVerdict(
    public val ready: Boolean,
    /** Empty when ready. One line per reason otherwise, each naming what is wrong. */
    public val reasons: List<String>,
) {
    override fun toString(): String = if (ready) "ready" else "not ready: ${reasons.joinToString("; ")}"
}

/**
 * Whether this process is willing to be sent traffic, and why not when it is not.
 *
 * Two inputs, and they fail for different reasons. The **checks** say whether the dependencies
 * answered; the **shutdown latch** says whether the process has started stopping. Readiness is the
 * conjunction, and a probe that could not tell them apart would report a draining pod and a broken
 * database identically.
 *
 * ## The latch is one-way, deliberately
 *
 * Once a shutdown has begun it does not un-begin. A gate that could go back to ready would let a
 * process that is halfway through closing its pools advertise itself to a load balancer — and the
 * announce stage's whole purpose is that the advertisement stops *before* anything closes.
 */
public class ReadinessGate(
    /**
     * `null` for a service with no dependency checks, which is a legitimate answer rather than a
     * placeholder: readiness is then purely about whether the process is stopping.
     */
    private val checks: HealthRegistry? = null,
) {
    /**
     * `@Volatile` rather than an atomic, and the reason is that nothing needs more.
     *
     * A compare-and-set would let [beginShutdown] report whether *this* call was the one that
     * flipped it — and nothing wants to know. The sequence runs exactly once by the stage machine's
     * own guard, so a second flip is already impossible where it matters, and
     * `kotlin.concurrent.atomics` is still experimental in common code. What a latch genuinely needs
     * is that a write on one thread is seen by the reader on another, which is what volatile is.
     */
    @Volatile
    private var shuttingDown: Boolean = false

    /** What [io.github.youndie.kore.ktor.installShutdownRefusal] asks, and what the drain refusal is gated on. */
    public val isShuttingDown: Boolean get() = shuttingDown

    /** Flips the latch. Idempotent, and one-way: see the note on this class. */
    public fun beginShutdown() {
        shuttingDown = true
    }

    public suspend fun verdict(): ReadinessVerdict {
        val reasons = mutableListOf<String>()

        if (isShuttingDown) reasons += "shutting down"

        checks?.snapshot()?.forEach { result ->
            if (!result.status.isHealthy) reasons += result.toString()
        }

        return ReadinessVerdict(reasons.isEmpty(), reasons.toList())
    }
}
