package io.github.youndie.kore.ktor

import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.DrainGate
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.github.youndie.kore.signal.installShutdownSignalWatch
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/** The announce the parent probes through. Long enough for a dozen probes, short enough for a test. */
internal val HOOK_SUBJECT_ANNOUNCE = 2.seconds

/**
 * The service [KtorShutdownHookJvmTest] sends `SIGTERM` to, in a JVM of its own.
 *
 * A JVM of its own because the thing under test is fixed once per process — Ktor reads its switch on
 * the first `start()` — so the test's JVM, where other suites have started servers already, cannot
 * be the subject. Three wirings, chosen by the first argument:
 *
 * * `kore` — [startForKore] and [EngineDrain], what a consumer writes;
 * * `plain` — `start()` and a drain that calls `stop` by hand, past the guard. The **control**: it is
 *   what every kore-wired JVM service was until #90, and it has to go red or the test proves nothing;
 * * `guarded` — `start()` and [EngineDrain], which must refuse before anything is served.
 */
internal object HookSubject {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.single()
        val startup = StartupGate()
        val readiness = ReadinessGate()
        val server =
            embeddedServer(CIO, port = 0) {
                installKoreProbes(startup, readiness)
            }
        if (mode == "kore") server.startForKore() else server.start(wait = false)

        val drain: ShutdownParticipant =
            when (mode) {
                "plain" ->
                    object : ShutdownParticipant {
                        override val name = "http engine, by hand"

                        override suspend fun stop() = server.stop(1_000, 2_000)
                    }
                else ->
                    try {
                        EngineDrain(server, grace = 1.seconds, timeout = 2.seconds, gate = DrainGate())
                    } catch (refused: IllegalStateException) {
                        println("REFUSED ${refused.message}")
                        System.out.flush()
                        exitProcess(3)
                    }
            }

        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        startup.markStarted()
        runBlocking {
            runUntilSignal(
                ShutdownDeadlines(preDrainWait = HOOK_SUBJECT_ANNOUNCE, drain = 3.seconds, releaseGroup = 1.seconds),
                watch = installShutdownSignalWatch().also {
                    // AFTER the watch, so a signal the parent sends on seeing this line is one kore hears.
                    println("PORT=$port")
                    System.out.flush()
                },
            ) {
                announce(AnnounceNotReady(readiness))
                drain(drain)
            }
        }
    }
}
