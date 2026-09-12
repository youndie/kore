package io.github.youndie.kore.readme

import io.github.youndie.kore.booblik.FlushThenClose
import io.github.youndie.kore.booblik.booblikParticipant
import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.systemEnvironment
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.ktor.installKoreProbes
import io.github.youndie.kore.ktor.installKoreVersion
import io.github.youndie.kore.observability.ObservabilitySettings
import io.github.youndie.kore.observability.installKoreObservability
import io.github.youndie.kore.version.BuildIdentity
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.ktor.server.application.Application
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * **The README's snippets, as code the compiler reads.**
 *
 * An example is code nobody compiles, and this repository has already published one that could not
 * run: `println(run.transcript)` on the line after `runUntilSignal`, which on the JVM is on the
 * losing side of the exit race. Reading it again would not have caught that, and did not — a
 * consumer adopting the library did, and filed
 * [#59](https://github.com/youndie/kore/issues/59).
 *
 * So `./gradlew build` now fails when a snippet in `README.md` stops typing. That is a narrower
 * promise than "the example works": it catches a signature that moved, not a placement that races.
 * The behaviour is the sample's and the oracle's job — and #59 is in both of those now too.
 *
 * Keep these in step with the file by hand. A generator that extracted them would be a second thing
 * to be wrong, and there are two snippets.
 */
internal fun readmeShutdown(
    server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    deadlines: ShutdownDeadlines,
    readiness: ReadinessGate,
    startup: StartupGate,
    producer: FlushThenClose,
    myPool: ShutdownParticipant,
) {
    server.start(wait = false)
    startup.markStarted()

    runBlocking {
        runUntilSignal(
            deadlines,
            onFinished = { run -> println(run.transcript) },
        ) {
            announce(AnnounceNotReady(readiness))
            drain(EngineDrain(server, deadlines.drain, deadlines.drain + 5.seconds))
            consumer(booblikParticipant("events", producer))
            pool(myPool)
        }
    }
}

/**
 * The second snippet.
 *
 * **One substitution, and it is named rather than hidden.** The README passes `KoreBuildIdentity`,
 * which the Gradle plugin generates into the module that applies it; applying the plugin here would
 * make a module about documentation into a second consumer of the build identity, with generated
 * source and reasons of its own to break. `installKoreVersion` takes a `BuildIdentity`, so the
 * signature is what is checked and a hand-made one checks it. `samples/service` compiles the
 * plugin's real output, and the oracle refuses an image whose kore arm does not serve `/version`.
 */
internal fun Application.readmeInstalls(
    startup: StartupGate,
    readiness: ReadinessGate,
    liveness: LivenessGate,
    settings: ObservabilitySettings,
    myKeys: List<ConfigKey<*>>,
) {
    installKoreProbes(startup, readiness, liveness)
    installKoreVersion(HandMade)
    installKoreObservability(settings)

    val config = ConfigSchema("MYAPP", keys = myKeys).read(systemEnvironment())
    check(config.prefix == "MYAPP")
}

/** Stands in for the plugin's generated object — see [readmeInstalls]. */
private object HandMade : BuildIdentity {
    override val version: String = "0.0.0"
    override val commit: String = BuildIdentity.UNKNOWN
    override val dirty: Boolean = false
    override val builtAt: String = "1970-01-01T00:00:00Z"
}
