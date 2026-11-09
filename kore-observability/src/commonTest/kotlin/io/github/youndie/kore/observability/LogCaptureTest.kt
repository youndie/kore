package io.github.youndie.kore.observability

import io.github.youndie.tracy.agent.LogBuilder
import io.github.youndie.tracy.agent.RecordSink
import io.github.youndie.tracy.agent.TracyTraceContext
import io.github.youndie.tracy.wire.Level
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** Writes one line through the platform's own logging: SLF4J on the JVM, kotlin-logging on native. */
internal expect fun hostLog(
    level: Level,
    logger: String,
    message: String,
)

/** Whether this platform's logging can be reached at all — see [attachLogCapture]. */
internal expect val captureAvailable: Boolean

private class Collecting : RecordSink {
    val lines = mutableListOf<Pair<Level, String>>()

    override fun isEnabled(level: Level): Boolean = true

    override fun accept(
        level: Level,
        logger: String,
        message: String,
        cause: Throwable?,
        builder: LogBuilder,
        trace: TracyTraceContext?,
        untrusted: Boolean,
    ) {
        lines += level to message
    }
}

/**
 * B-71: kore wired tracy's agent, plugin and delivery and attached no capture, so a consumer that did
 * everything kore asked sent spans and not one log record (youndie/tracy M-150).
 */
class LogCaptureTest {
    @Test
    fun `a warning the service logs reaches tracy and an info line does not`() {
        val sink = Collecting()
        val detach = attachLogCapture(FromLevel(sink, Level.WARN))
        if (!captureAvailable) {
            // Absent, and saying so: a capture that could not attach must not look attached.
            assertNull(detach)
            return
        }
        try {
            hostLog(Level.INFO, "OrdersRouting", "order created")
            hostLog(Level.WARN, "OrdersRouting", "disk almost full")

            assertEquals(listOf(Level.WARN to "disk almost full"), sink.lines)
        } finally {
            assertNotNull(detach).invoke()
        }
    }

    @Test
    fun `once detached the service's logging no longer reaches tracy`() {
        val sink = Collecting()
        val detach = attachLogCapture(sink) ?: return
        detach()

        hostLog(Level.ERROR, "OrdersRouting", "payment failed")

        assertEquals(emptyList(), sink.lines)
    }

    @Test
    fun `the level floor refuses what is below it and keeps what is at it`() {
        val floor = FromLevel(Collecting(), Level.WARN)

        assertFalse(floor.isEnabled(Level.INFO))
        assertEquals(true, floor.isEnabled(Level.WARN))
        assertEquals(true, floor.isEnabled(Level.ERROR))
    }

    @Test
    fun `the wiring attaches the capture by default`() =
        testApplication {
            var observability: KoreObservability? = null
            application { observability = installKoreObservability(settings(), flushGrace = 10.milliseconds) }
            client.get("/")

            assertEquals(captureAvailable, observability!!.logsCaptured)
            // Stopping is what takes the appender off again: the logging configuration is global.
            observability!!.stop()
        }

    @Test
    fun `switched off the wiring attaches none`() =
        testApplication {
            var observability: KoreObservability? = null
            application {
                observability = installKoreObservability(settings(tracyCaptureFrom = null), flushGrace = 10.milliseconds)
            }
            client.get("/")

            assertFalse(observability!!.logsCaptured)
            observability!!.stop()
        }

    private fun settings(tracyCaptureFrom: Level? = Level.WARN) =
        ObservabilitySettings(
            service = "orders",
            release = "1.0",
            instance = "pod-1",
            tracy = AgentEndpoint("http://127.0.0.1:1", "k"),
            tracyCaptureFrom = tracyCaptureFrom,
        )
}
