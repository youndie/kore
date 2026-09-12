package io.github.youndie.kore.observability

import io.github.youndie.katcher.Katcher
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.metrik.agent.Metrik
import io.github.youndie.tracy.agent.AgentConfig
import io.github.youndie.tracy.agent.Tracy
import io.github.youndie.tracy.agent.TracyAgent
import io.github.youndie.tracy.agent.TracyDelivery
import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What was installed, and the one thing there is to stop.
 *
 * Returned rather than registered, because the shutdown plan is assembled by the consumer and a
 * library that reached into it would be choosing the order — which is the one thing kore refuses to
 * let anything else choose. Hand it to `telemetry(...)`:
 *
 * ```kotlin
 * val observability = application.installKoreObservability(settings)
 * shutdownSequence(deadlines) { telemetry(observability) }
 * ```
 */
public class KoreObservability internal constructor(
    private val tracyDelivery: TracyDelivery?,
    private val flushGrace: Duration,
) : ShutdownParticipant {
    override val name: String = "observability"

    /**
     * Flushes what can be flushed, which is **tracy and only tracy**.
     *
     * The other two are not omissions and the reasons differ:
     *
     * * **metrik** — its plugin constructs the agent internally and publishes only the counters, so
     *   there is no handle to stop even if stopping helped; and `MetrikAgent.stop()` does not flush,
     *   so the open aggregation window is lost regardless of when it is called. The plugin also
     *   subscribes itself to `ApplicationStopping`, which on Kotlin/Native fires *before* the drain —
     *   so on a native binary the requests served during shutdown are not measured at all. None of
     *   that is fixable from here; it is `feature-observability-wiring` §7 and metrik's own issue.
     * * **katcher** — `start` is its only lifecycle function. There is no `stop` and no `flush`.
     *
     * The bound is kore's, not tracy's. `stop(grace)` defaults to the agent's flush interval, which
     * is a number chosen for steady state; a shutdown gets the telemetry stage's deadline instead,
     * and the stage cancels it if it overruns.
     */
    override suspend fun stop() {
        tracyDelivery?.stop(flushGrace)
    }
}

/**
 * The three agents in one call.
 *
 * Each gets the shutdown treatment it actually needs rather than the one an example happened to
 * have: `feature-observability-wiring` §3 has the table, read out of the agents rather than assumed.
 *
 * **tracy's plugin and its delivery are installed together or not at all.** They are two objects,
 * and a server with only the plugin logs into memory and reports nothing — the failure the toolkit's
 * own README example exists to prevent, and the one the portfolio's most complete service has.
 *
 * @param flushGrace how long the telemetry stage gives tracy's last flush. Defaults to kore's own
 *   release-group deadline rather than tracy's flush interval.
 * @param clock injected, because an agent stamping records off the wall clock is the one place a
 *   test cannot move time.
 */
public fun Application.installKoreObservability(
    settings: ObservabilitySettings,
    flushGrace: Duration = 3.seconds,
    clock: () -> Long = { defaultEpochMillis() },
): KoreObservability {
    require(settings.service.isNotBlank()) { "the service name is required: it is what every agent files its data under" }
    require(settings.service.none { it.isWhitespace() }) {
        "the service name must not contain whitespace: \"${settings.service}\" would file data under a name " +
            "nothing else in the portfolio can match"
    }

    // RULE 4, and it is a refusal rather than a default. Unset, katcher's own default is
    // `Unspecified`, and a crash group named `Unspecified` is a crash nobody can act on — the
    // deployment believes it is reporting and is reporting into a bucket that means nothing.
    val release = settings.release
    require(!settings.anyAgentOn || !release.isNullOrBlank()) {
        "a release identifier is required once any agent is on: it is what a crash group and a " +
            "deploy marker are named after, and katcher's own default for it is \"Unspecified\""
    }

    val delivery =
        settings.tracy?.let { tracy ->
            val agentConfig =
                AgentConfig(
                    service = settings.service,
                    apiKey = tracy.key,
                    endpoint = tracy.endpoint,
                    instanceId = settings.instance,
                    release = release,
                )
            val agent = TracyAgent(agentConfig, clock = clock)

            // BOTH, always. The plugin fills a buffer; the delivery is what empties it.
            val started = TracyDelivery(agent, agentConfig)
            started.start(this)
            install(Tracy) { this.agent = agent }
            started
        }

    settings.metrik?.let { metrik ->
        install(Metrik) {
            service = settings.service
            apiKey = metrik.key
            endpoint = metrik.endpoint
            instanceId = settings.instance
            this.release = release
        }
    }

    settings.katcher?.let { katcher ->
        Katcher.start {
            appKey = katcher.key
            remoteHost = katcher.endpoint
            // `release` is non-null here: `anyAgentOn` is true and the refusal above has run.
            this.release = release ?: ""
            environment = settings.environment
        }
    }

    return KoreObservability(delivery, flushGrace)
}

/**
 * `kotlin.time.Clock`, not an `expect`/`actual` pair: the standard library has had one since 2.1 and
 * a platform split for one number would be two files to keep in step for nothing.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
internal fun defaultEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
