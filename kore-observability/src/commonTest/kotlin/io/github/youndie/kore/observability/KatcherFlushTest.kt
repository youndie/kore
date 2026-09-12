package io.github.youndie.kore.observability

import io.github.youndie.katcher.Katcher
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **A crash filed during shutdown leaves the process**, against a real receiver on a real socket.
 *
 * This is the half of the telemetry stage that did not exist until katcher client 0.7.47. `start` was
 * katcher's only lifecycle function, so a report the client could not deliver waited on disk for the
 * *next* launch — right for a phone, wrong for a container that may not get one. That is what
 * [youndie/katcher#50](https://github.com/youndie/katcher/issues/50) asked and what `flush(grace)`
 * answers; this asserts kore actually calls it, because a version bump nothing calls changes nothing.
 *
 * ## The receiver refuses first, and that is the whole test
 *
 * `Katcher.catch()` signals a background uploader on `Dispatchers.IO`. Against a receiver that
 * answers `200` straight away, **that** uploader delivers the report within milliseconds and the
 * assertion passes with `stop()` removed — a test of katcher's worker wearing kore's name. Checked
 * by deleting the `Katcher.flush` line: green.
 *
 * So the receiver refuses until the uploader has given up. katcher's queue wakes on a signal and
 * nothing signals it again, so from that moment the report moves only if something asks — and the
 * only thing that asks is the telemetry stage.
 *
 * Shaped like [TracyFlushTest] otherwise, and for the same reason: two real engines and no test host.
 * A `testApplication { }` nested in `runTest { }` hung on Kotlin/Native for over thirty minutes, and
 * a virtual clock has no business in a test whose subject is a network round trip.
 */
class KatcherFlushTest {
    private class Receiver {
        /** Written by the server threads, read by the test. Volatile is enough: one writer at a time. */
        @Volatile
        var refuse: Boolean = true

        @Volatile
        var attempts: Int = 0

        @Volatile
        var delivered: String? = null
    }

    @Test
    fun `a crash the uploader could not deliver is flushed out by the telemetry stage`() = runTest {
        val receiver = Receiver()
        withContext(Dispatchers.Default) {
            val collector =
                embeddedServer(CIO, port = 0) {
                    routing {
                        // katcher posts to `api/reports`; this takes whatever it posts to, so a
                        // changed path fails on the assertion rather than on a 404 nobody reads.
                        post("/{...}") {
                            receiver.attempts += 1
                            if (receiver.refuse) {
                                call.respondText("no", status = HttpStatusCode.ServiceUnavailable)
                            } else {
                                receiver.delivered = call.receiveText()
                                call.respondText("ok")
                            }
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

            awaitQuiet(receiver)
            assertTrue(
                receiver.attempts > 0,
                "katcher never tried at all — the client was not started, or not with this endpoint",
            )
            receiver.refuse = false
            val beforeStop = receiver.attempts

            // The telemetry stage's job, called directly. What the stage adds is the deadline, and
            // that is asserted separately in ObservabilityInstallTest.
            wired.stop()
            println("[probe] attempts before stop=$beforeStop after=${receiver.attempts} flush=${Katcher.flush(5.seconds)}")

            subject.stop(0, 0)
            collector.stop(0, 0)
        }

        val delivered = receiver.delivered
        assertTrue(
            delivered != null,
            "the crash report stayed on disk: the telemetry stage did not flush katcher",
        )
        assertTrue(
            delivered.contains(CRASH),
            "something was delivered, but not the crash filed during the shutdown: $delivered",
        )
    }

    /**
     * Waits until the uploader stops knocking — a quiet window rather than a count, because the
     * count is katcher's retry policy and this test has no business pinning it.
     */
    private suspend fun awaitQuiet(receiver: Receiver) {
        var seen = -1
        while (seen != receiver.attempts) {
            seen = receiver.attempts
            delay(QUIET.inWholeMilliseconds)
        }
    }

    private companion object {
        /**
         * Unique to this test, not "boom". katcher's queue is a directory on disk that outlives the
         * process, so a report left by an earlier run could be delivered here and make a vacuous
         * assertion pass.
         */
        private const val CRASH = "kore telemetry stage must deliver this"

        /** Longer than katcher's `exponentialDelay(maxDelayMs = 3_000)` between retries. */
        private val QUIET = 4_000.milliseconds
    }
}
