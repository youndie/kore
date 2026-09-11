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

public fun Application.sampleModule(consumer: StandInConsumer = StandInConsumer()) {
    routing {
        // THE SLOW ROUTE. Its delay is a parameter so the oracle can print what it used with the
        // result: a run where nothing was in flight at the signal is inconclusive, not a pass.
        get("/work") {
            val millis = call.request.queryParameters["ms"]?.toLongOrNull() ?: DEFAULT_WORK_MILLIS
            delay(millis)
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
        log.info("stopping: closing the consumer")
        // Not even suspending here, because the idiomatic subscriber cannot suspend — which is its
        // own half of the problem and why kore's release stage is not a Ktor subscriber.
        consumer.stopped
    }
}

/** Starts the server and blocks. Identical on both targets; only the `main` above it differs. */
public fun startSample(port: Int = SAMPLE_PORT) {
    embeddedServer(CIO, port = port, host = "0.0.0.0") { sampleModule() }.start(wait = true)
}
