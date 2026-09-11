package io.github.youndie.kore.sample

import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay

/**
 * The fixture the library is judged by — **not a demonstration**.
 *
 * Every line here exists because an assertion reads it, and it is deliberately wired the way
 * everybody wires a Ktor service: one `/health` that answers `ok`, one `ApplicationStopping`
 * subscriber that closes things, and `embeddedServer(...).start(wait = true)`. That is the shape
 * `docs/research/research-oracle.md` §1 calls the **negative control**: the run that has to break
 * before kore is worth building.
 *
 * Keeping it wired wrongly is the point. Once kore works, the comparison can be re-run — and
 * deleting this variant would remove the only evidence that kore does anything.
 */
public const val DEFAULT_WORK_MILLIS: Long = 2_000

/** The port. A constant until there is a configuration schema to read one with — that is B-21. */
public const val SAMPLE_PORT: Int = 8080

/**
 * Stands in for a message consumer: something holding a position that has to be told to stop.
 *
 * It records what happened to it so a test can ask, which is the whole reason it is not a comment.
 */
public class StandInConsumer : ShutdownParticipant {
    override val name: String = "stand-in consumer"

    public var stopped: Boolean = false
        private set

    override suspend fun stop() {
        // A flush, standing in for the one a real producer needs before it is closed.
        delay(50)
        stopped = true
    }
}

/**
 * Something an in-flight request needs, which a shutdown subscriber closes.
 *
 * This is the whole of research §1.1 made observable. Without it the control's `ApplicationStopping`
 * subscriber does nothing, so the ordering it is supposed to demonstrate has no consequence — and
 * twelve green runs say nothing about the claim the library is built on. Stand-in for a connection
 * pool, which is the real thing services close there.
 */
public class FragileResource {
    @kotlin.concurrent.Volatile
    public var closed: Boolean = false

    /** Called by the slow route **after** its delay — that is, while the request is in flight. */
    public fun use() {
        check(!closed) { "the resource was closed while this request was still being served" }
    }
}

public fun Application.sampleModule(
    consumer: StandInConsumer = StandInConsumer(),
    resource: FragileResource = FragileResource(),
    /**
     * Whether the `ApplicationStopping` subscriber closes [resource] — the ordinary thing a service
     * does there, and the thing research §1.1 says is unsafe on one of the two platforms.
     */
    closeOnStop: Boolean = false,
) {
    routing {
        // THE SLOW ROUTE. Its delay is a parameter so the oracle can print what it used with the
        // result: a run where nothing was in flight at the signal is inconclusive, not a pass.
        get("/work") {
            val millis = call.request.queryParameters["ms"]?.toLongOrNull() ?: DEFAULT_WORK_MILLIS
            delay(millis)
            // AFTER the delay, on purpose: the request has to still be in flight when the subscriber
            // runs, or the experiment measures nothing.
            resource.use()
            call.respondText("worked for ${millis}ms\n")
        }

        // One route for all three probes, which is exactly what the first consumer does today and
        // exactly what `docs/features/feature-health-probes.md` exists to stop. Left alone here on
        // purpose: this is the control.
        get("/health") { call.respondText("ok\n") }
    }

    // THE ORDINARY WIRING, and on Kotlin/Native it runs BEFORE in-flight requests have finished
    // (research §1.1). That is the defect the negative control has to demonstrate.
    monitor.subscribe(ApplicationStopping) {
        log.info("stopping: closing what this service holds")
        // Not suspending, because the idiomatic subscriber cannot — which is its own half of the
        // problem and why kore's release stage is not a Ktor subscriber.
        consumer.stopped
        if (closeOnStop) resource.closed = true
    }
}

/**
 * How this run of the control is configured.
 *
 * Arguments rather than environment variables, and that is not a preference: reading the environment
 * on Kotlin/Native needs an `expect`/`actual` pair, which is what [feature-typed-config] is for and
 * does not exist yet. `main(args)` exists on both targets and needs nothing.
 */
public class SampleOptions(
    public val port: Int = SAMPLE_PORT,
    /**
     * `null` means **do not configure it** — Ktor's own default of 1000 ms, which is the true
     * control. A number here is the variant that lets the ordering question be visible at all
     * (research §1.13).
     */
    public val shutdownGraceMillis: Long? = null,
    /** Whether the stop subscriber closes the resource the slow route uses. Off by default. */
    public val closeOnStop: Boolean = false,
) {
    public companion object {
        public fun parse(args: Array<String>): SampleOptions {
            val map =
                args.mapNotNull { argument ->
                    val clean = argument.removePrefix("--")
                    val equals = clean.indexOf('=')
                    if (equals > 0) clean.take(equals) to clean.drop(equals + 1) else null
                }.toMap()
            return SampleOptions(
                port = map["port"]?.toIntOrNull() ?: SAMPLE_PORT,
                shutdownGraceMillis = map["grace"]?.toLongOrNull(),
                closeOnStop = map["close-on-stop"] == "true",
            )
        }
    }
}

/** Starts the server and blocks. Identical on both targets; only the `main` above it differs. */
public fun startSample(options: SampleOptions = SampleOptions()) {
    val server =
        embeddedServer(
            CIO,
            configure = {
                connectors.add(
                    io.ktor.server.engine.EngineConnectorBuilder().apply {
                        port = options.port
                        host = "0.0.0.0"
                    },
                )
                options.shutdownGraceMillis?.let { grace ->
                    // Only when asked. Left alone, this is Ktor's 1000 ms — shorter than a great
                    // many real requests, and the whole reason the control has two settings.
                    shutdownGracePeriod = grace
                    shutdownTimeout = grace + 5_000
                }
            },
            module = { sampleModule(closeOnStop = options.closeOnStop) },
        )
    server.start(wait = true)
}
