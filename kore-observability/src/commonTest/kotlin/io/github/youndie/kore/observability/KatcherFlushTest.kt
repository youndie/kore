package io.github.youndie.kore.observability

import io.github.youndie.katcher.Katcher
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **A crash filed during shutdown leaves the process**, against a real receiver on a real socket.
 *
 * This is the half of the telemetry stage that did not exist until katcher client 0.7.47. `start` was
 * katcher's only lifecycle function, so the report was written to disk and delivered by the *next*
 * launch — right for a phone, wrong for a container that may not get one. That is what
 * [youndie/katcher#50](https://github.com/youndie/katcher/issues/50) asked and what `flush(grace)`
 * answers; this asserts kore actually calls it, because a version bump that nothing calls changes
 * nothing (`feature-observability-wiring` §7).
 *
 * Shaped like [TracyFlushTest] and for the same reason: two real engines and no test host. A
 * `testApplication { }` nested in `runTest { }` hung on Kotlin/Native for over thirty minutes there,
 * and a virtual clock has no business in a test whose subject is a network round trip.
 */
class KatcherFlushTest {
    private class Receiver {
        /** One write, one read, from different threads. A count would need an atomic; this does not. */
        @Volatile
        var body: String? = null
    }

    @Test
    fun `a crash filed before the stop is delivered by the telemetry stage`() = runTest {
        val receiver = Receiver()
        withContext(Dispatchers.Default) {
            val collector =
                embeddedServer(CIO, port = 0) {
                    routing {
                        // katcher posts to `api/reports`; this takes whatever it posts to, so a
                        // changed path fails on the assertion rather than on a 404 nobody reads.
                        post("/{...}") {
                            receiver.body = call.receiveText()
                            call.respondText("ok")
                        }
                    }
                }
            collector.start(wait = false)
            val port = collector.engine.resolvedConnectors().first().port

            var observability: KoreObservability? = null
            val subject =
                embeddedServer(CIO, port = 0) {
                    observability =
                        installKoreObservability(
                            ObservabilitySettings(
                                service = "orders",
                                release = "1.4.0+abc123",
                                instance = "pod-1",
                                katcher = AgentEndpoint("http://127.0.0.1:$port", "key"),
                            ),
                            flushGrace = 5.seconds,
                        )
                }
            subject.start(wait = false)
            subject.engine.resolvedConnectors()

            val wired = observability
            assertTrue(wired != null, "the module never ran")

            // What a service does on its way down: something failed while the drain was running.
            Katcher.catch(IllegalStateException(CRASH))

            // The telemetry stage's job, called directly. What the stage adds is the deadline, and
            // that is asserted separately in ObservabilityInstallTest.
            wired.stop()

            subject.stop(0, 0)
            collector.stop(0, 0)
        }

        val delivered = receiver.body
        assertTrue(
            delivered != null,
            "the crash report never left the process: katcher was either not started or not flushed",
        )
        assertTrue(
            delivered.contains(CRASH),
            "something was delivered, but not the crash filed during the shutdown: $delivered",
        )
    }

    private companion object {
        /**
         * Unique to this test, not "boom". katcher's queue is a directory on disk that outlives the
         * process, so a report left by an earlier run could be delivered here and make a vacuous
         * assertion pass.
         */
        private const val CRASH = "kore telemetry stage must deliver this"
    }
}
