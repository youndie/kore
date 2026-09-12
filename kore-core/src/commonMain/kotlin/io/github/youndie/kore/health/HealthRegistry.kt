package io.github.youndie.kore.health

import io.github.youndie.kore.concurrent.KoreDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Runs the checks on a loop and remembers what they said.
 *
 * **The probe reads this, it does not run the checks.** A check on the request path can hang, and a
 * readiness probe that hangs has its answer decided by `timeoutSeconds`, whose Kubernetes default is
 * one second (research Risk 3, §1.10). The cache is also what makes a two-second readiness period
 * affordable: the cost of probing stops being proportional to how often the kubelet asks.
 *
 * The price is that a failure is noticed one refresh interval late, and that is **stated** in every
 * answer as an age rather than hidden.
 */
public class HealthRegistry(
    private val checks: List<HealthCheck>,
    /** How often the loop asks. Half the readiness probe period, so a probe rarely reads a stale one. */
    public val refreshInterval: Duration = 2.seconds,
    /**
     * Past this, a result stops counting as an answer.
     *
     * Not the same as the refresh interval: a check that overruns leaves the previous result in
     * place, and without this it would go on being served as though it were current.
     */
    public val staleAfter: Duration = 10.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Remembered(val status: HealthStatus, val message: String?, val at: TimeMark)

    private val guard = Mutex()
    private val remembered = HashMap<String, Remembered>()
    private var loop: Job? = null

    /**
     * Starts the refresh loop. Idempotent; a second call is ignored rather than starting a second loop.
     *
     * The loop runs on [KoreDispatchers.checks] and **not** on whatever the caller was on, because a
     * check can block its thread and the caller is usually holding the one the shutdown sequence
     * needs (B-38). Pass [context] to put it somewhere else — a test with a virtual clock passes
     * `EmptyCoroutineContext` to stay on the test dispatcher.
     */
    public fun start(scope: CoroutineScope, context: CoroutineContext = KoreDispatchers.checks) {
        if (loop != null) return
        loop = scope.launch(context) { refreshForever() }
    }

    public fun stop() {
        loop?.cancel()
        loop = null
    }

    /** What every check last said. A check that has never answered appears as [HealthStatus.UNKNOWN]. */
    public suspend fun snapshot(): List<HealthResult> =
        guard.withLock {
            checks.map { check ->
                val last = remembered[check.name]
                when {
                    last == null -> HealthResult(check.name, HealthStatus.UNKNOWN, "has not run yet", null)
                    last.at.elapsedNow() > staleAfter ->
                        HealthResult(
                            check.name,
                            HealthStatus.UNKNOWN,
                            "last answered ${last.at.elapsedNow()} ago, past the ${staleAfter} budget",
                            last.at.elapsedNow(),
                        )
                    else -> HealthResult(check.name, last.status, last.message, last.at.elapsedNow())
                }
            }
        }

    /** True only when every check last answered healthy **and** recently enough to count. */
    public suspend fun allHealthy(): Boolean = snapshot().all { it.status.isHealthy }

    /** One pass. Public so a test drives it a step at a time: a test that waits on the loop's own timer measures the timer. */
    public suspend fun refreshOnce() {
        checks.forEach { check ->
            val outcome =
                try {
                    // Bounded per check, so a slow dependency produces a stale result rather than a
                    // backlog of overlapping checks. It does NOT interrupt a blocking call — see the
                    // quirk in the feature document; that is B-38's question, not this one's.
                    val finished = withTimeoutOrNull(check.timeout) { check.check(); true }
                    if (finished == null) {
                        Remembered(HealthStatus.UNHEALTHY, "did not answer within ${check.timeout}", timeSource.markNow())
                    } else {
                        Remembered(HealthStatus.HEALTHY, null, timeSource.markNow())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Remembered(HealthStatus.UNHEALTHY, failure.message ?: failure::class.simpleName, timeSource.markNow())
                }

            guard.withLock { remembered[check.name] = outcome }
        }
    }

    private suspend fun refreshForever() {
        while (true) {
            refreshOnce()
            delay(refreshInterval)
        }
    }
}
