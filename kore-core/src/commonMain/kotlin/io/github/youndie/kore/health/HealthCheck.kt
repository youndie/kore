package io.github.youndie.kore.health

import kotlin.time.Duration

/**
 * One dependency, asked one question.
 *
 * **It must prove the dependency answered, not that a handle was produced.** For a pool that means
 * running a trivial statement: `acquire()` can hand back an idle connection whose far end is gone,
 * and sqlx4k's pool has no `ping` to ask instead (research §1.9). A check that reports healthy
 * through an outage is worse than no check, because a deployment reads it as evidence.
 */
public interface HealthCheck {
    /** Read by a person during an incident, so `"orders database"` beats `"Check@4f8e"`. */
    public val name: String

    /** How long this check gets before its result is called stale rather than awaited. */
    public val timeout: Duration

    /** Throws to fail. The message becomes the reason a probe gives. */
    public suspend fun check()
}

/** What a check last answered, and when. */
public class HealthResult(
    public val name: String,
    public val status: HealthStatus,
    /** Why it failed, or why it is unknown. `null` when healthy. */
    public val message: String?,
    /** How long ago the answer was taken. `null` when there has never been one. */
    public val age: Duration?,
) {
    override fun toString(): String =
        "$name ${status.name.lowercase()}" +
            (age?.let { " ${it}ago" } ?: " never run") +
            (message?.let { " — $it" } ?: "")
}

public enum class HealthStatus {
    HEALTHY,
    UNHEALTHY,

    /**
     * Never answered, or answered too long ago to count.
     *
     * A first-class value rather than a flavour of healthy, and both halves matter. A process whose
     * first refresh has not finished has not earned a `200`; and a cache that keeps returning the
     * last good value is a probe that reports health straight through an outage — which is the
     * failure this whole feature exists to end.
     */
    UNKNOWN,
    ;

    public val isHealthy: Boolean get() = this == HEALTHY
}
