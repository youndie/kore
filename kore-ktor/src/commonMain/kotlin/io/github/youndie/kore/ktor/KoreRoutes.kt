package io.github.youndie.kore.ktor

/**
 * The paths kore reserves, in one place.
 *
 * They are the contract of `docs/api/endpoint-kore-admin.md` and they are needed here rather than
 * only where the routes are served, because [installShutdownRefusal] has to know which requests it
 * must **not** refuse. The routes themselves are B-17.
 */
public object KoreRoutes {
    public const val STARTUP: String = "/health/startup"
    public const val READY: String = "/health/ready"
    public const val LIVE: String = "/health/live"

    /** The alias every chart in the portfolio already names. Liveness, not readiness. */
    public const val HEALTH: String = "/health"
    public const val VERSION: String = "/version"

    /**
     * What a shutdown must go on answering.
     *
     * **Not `/health/ready`** — readiness is *supposed* to fail during a shutdown, and refusing it
     * with kore's own `503` produces the same answer by a different route. The rest are here because
     * refusing them would be actively harmful: a `503` from `/health/live` is a failed liveness
     * probe, and enough of those restart the pod **in the middle of the shutdown it is reporting**.
     */
    public val servedWhileShuttingDown: Set<String> = setOf(STARTUP, LIVE, HEALTH, VERSION, READY)
}
