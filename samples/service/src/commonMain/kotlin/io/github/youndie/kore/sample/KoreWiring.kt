package io.github.youndie.kore.sample

import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.ktor.installKoreProbes
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.ShutdownTranscript
import io.github.youndie.kore.lifecycle.shutdownSequence
import io.github.youndie.kore.signal.installShutdownSignalWatch
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * The same sample, wired **with** kore. The other arm of the experiment.
 *
 * `samples/service`'s control form is wired the way everybody wires a Ktor service, and the negative
 * control measured what that costs. This is what kore is supposed to change, and it is a separate
 * entry point rather than a replacement so the comparison keeps both arms: deleting the control
 * would remove the only evidence that kore does anything.
 *
 * ## The assembly, and what it answers
 *
 * Everything here is a *consumer* writing wiring — no part of it lives inside the library — which is
 * the shape open question 2 of the research is about. Read it as the current answer to "does kore own
 * the entry point": it does not, and this is what that costs in lines.
 */
public fun startKoreSample(options: SampleOptions) {
    val readiness = ReadinessGate()
    val startup = StartupGate()
    val liveness = LivenessGate()
    val consumer = StandInConsumer()

    /**
     * The same resource the control closes in `ApplicationStopping`, and it **must** be closed here
     * too or the comparison is worthless.
     *
     * The control's defect is not "it closes a pool"; it is *when*. An arm that closed nothing would
     * pass A2 by not doing the thing, and the experiment would be comparing a service that closes a
     * resource against one that does not — which proves nothing about ordering. So kore's arm closes
     * exactly the same resource, as a release-stage participant, **after** the drain.
     */
    val resource = FragileResource()

    val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        embeddedServer(
            CIO,
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = options.port
                        host = "0.0.0.0"
                    },
                )
                // kore owns these numbers rather than inheriting Ktor's 1000 ms, which is shorter
                // than a great many real requests (research §1.13). The engine still needs them,
                // because `stopSuspend` is given them explicitly and a caller elsewhere might not be.
                shutdownGracePeriod = options.deadlines.drain.inWholeMilliseconds
                shutdownTimeout = options.deadlines.drain.inWholeMilliseconds + 5_000
            },
            module = {
                koreSampleModule(readiness, startup, liveness, resource)
            },
        )

    // NOT `start(wait = true)`. The main thread has to be free to wait for the signal and then run
    // the sequence — which is the whole reason kore does not go through `addShutdownHook`.
    server.start(wait = false)
    startup.markStarted()

    val watch = installShutdownSignalWatch()

    runBlocking {
        watch.awaitSignal()

        val transcript =
            shutdownSequence(options.deadlines) {
                announce(AnnounceNotReady(readiness))
                drain(EngineDrain(server, options.deadlines.drain, options.deadlines.drain + 5.seconds))
                consumer(consumer)
                // The same close the control does in `ApplicationStopping` — here, after the drain.
                // This is the treatment the experiment is testing, not a detail.
                pool(
                    object : ShutdownParticipant {
                        override val name = "fragile resource"

                        override suspend fun stop() {
                            resource.closed = true
                        }
                    },
                )
            }.run()

        // Printed rather than logged through anything clever: the oracle asserts from the client's
        // record, and this is for a person reading `docs logs` afterwards.
        println(transcript.describe())
        watch.releaseProcess()
    }
}

private fun ShutdownTranscript.describe(): String =
    "kore shutdown transcript:\n" + stages.joinToString("\n") { "  $it" }

/** The sample's routes plus everything kore mounts. */
public fun Application.koreSampleModule(
    readiness: ReadinessGate,
    startup: StartupGate,
    liveness: LivenessGate,
    resource: FragileResource = FragileResource(),
) {
    // BEFORE the probes and the routes: an interceptor installed later would let calls through that
    // arrived first, and the one thing this must never miss is the first request after the announce.
    installShutdownRefusal(isShuttingDown = { readiness.isShuttingDown })
    installKoreProbes(startup, readiness, liveness)
    workRoutes(resource)
}
