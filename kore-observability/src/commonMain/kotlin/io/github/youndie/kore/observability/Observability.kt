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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    /**
     * The tracy agent, or `null` when tracy is off — **because a service has to be able to log**.
     *
     * Handed back rather than hidden: `agent.logger("name")` is how anything gets into tracy at all,
     * and a wiring call that installed the agent and kept it to itself would leave a consumer with
     * the plugin's sampled request spans and no way to write a line. That is the same shape as
     * installing a buffer with nothing to empty it, one level up.
     *
     * It makes tracy part of this module's API rather than an implementation detail, which is honest:
     * `kore-observability` wires three named agents and says so in its name.
     */
    public val tracy: TracyAgent?,
    private val tracyDelivery: TracyDelivery?,
    private val flushGrace: Duration,
) : ShutdownParticipant {
    override val name: String = "observability"

    /**
     * Flushes what can be flushed: **tracy and katcher**. metrik still cannot be, and that reason is
     * its own — the plugin constructs the agent internally and publishes only the counters, so there
     * is no handle to stop even if stopping helped; `MetrikAgent.stop()` does not flush, so the open
     * aggregation window is lost regardless of when it is called; and the plugin subscribes itself to
     * `ApplicationStopping`, which on Kotlin/Native fires *before* the drain, so on a native binary
     * the requests served during shutdown are not measured at all. None of that is fixable from here;
     * it is `feature-observability-wiring` §7 and metrik's own issue.
     *
     * katcher used to be in that paragraph — `start` was its only lifecycle function — and that is
     * what [youndie/katcher#50](https://github.com/youndie/katcher/issues/50) asked about. Since
     * client 0.7.47 there is `flush(grace)`, so the crash report a service files while it is shutting
     * down leaves the process instead of waiting on disk for a next launch the container may not get.
     *
     * **The two run concurrently, each bounded by [flushGrace].** Sequentially, tracy's last flush
     * could spend the whole budget and katcher would be handed a deadline that had already passed —
     * a stage deadline is not a queue. Concurrently the stage still pays [flushGrace] once.
     *
     * The bound is kore's, not the agents'. tracy's `stop(grace)` defaults to the agent's flush
     * interval, which is a number chosen for steady state; a shutdown gets the telemetry stage's
     * deadline instead, and the stage cancels it if it overruns.
     */
    override suspend fun stop(): Unit =
        coroutineScope {
            launch { tracyDelivery?.stop(flushGrace) }
            // Unconditional, unlike tracy's: `Katcher` is a global object with no handle to hold, so
            // there is nothing here to be null. Before `start` it answers `true` — nothing to hand
            // over — which is the right answer for a service that never configured katcher.
            launch { Katcher.flush(flushGrace) }
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
 * @param flushGrace how long the telemetry stage gives the last flush of tracy and of katcher — each,
 *   because they run concurrently. Defaults to kore's own release-group deadline rather than tracy's
 *   flush interval.
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

    var agent: TracyAgent? = null
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
            val built = TracyAgent(agentConfig, clock = clock)
            agent = built

            // BOTH, always. The plugin fills a buffer; the delivery is what empties it.
            val started = TracyDelivery(built, agentConfig)
            started.start(this)
            install(Tracy) { this.agent = built }
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

    return KoreObservability(agent, delivery, flushGrace)
}

/**
 * `kotlin.time.Clock`, not an `expect`/`actual` pair: the standard library has had one since 2.1 and
 * a platform split for one number would be two files to keep in step for nothing.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
internal fun defaultEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
