package io.github.youndie.kore.observability

import io.ktor.client.request.get
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **The records actually leave the process**, against a real receiver on a real socket.
 *
 * This exists because a mutation survived without it. Installing tracy's plugin and *not* its
 * delivery passed every other test in this module: the plugin is visible from outside and the
 * delivery is not, so asserting the plugin proves only that a buffer was installed. A buffer with
 * nothing to empty it is exactly the failure rule 7 forbids and the one the portfolio's most complete
 * service has.
 *
 * Nothing here is a double. The agent is kore's, the delivery is kore's, the transport is HTTP over
 * loopback, and the assertion is that a request arrived somewhere else.
 */
class TracyFlushTest {
    private class Receiver {
        @Volatile
        var requests: Int = 0
    }

    @Test
    fun `a record written before the stop is delivered by the telemetry stage`() = runTest {
        val receiver = Receiver()
        val server =
            embeddedServer(CIO, port = 0) {
                routing {
                    // tracy posts its batch; the path is tracy's business, so this takes anything.
                    post("/{...}") {
                        receiver.requests++
                        call.respondText("ok")
                    }
                }
            }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port

        var observability: KoreObservability? = null
        testApplication {
            application {
                observability =
                    installKoreObservability(
                        ObservabilitySettings(
                            service = "orders",
                            release = "1.4.0+abc123",
                            instance = "pod-1",
                            tracy = AgentEndpoint("http://127.0.0.1:$port", "key"),
                        ),
                        flushGrace = 5.seconds,
                    )
            }
            client.get("/")

            val agent = observability!!.tracy
            assertTrue(agent != null, "tracy was configured and no agent was handed back to log with")
            agent.logger("test").info("a record that must survive the shutdown")

            // The telemetry stage's job, called directly: what the stage adds is the deadline, and
            // that is asserted separately in ObservabilityInstallTest.
            observability!!.stop()
        }

        server.stop(0, 0)
        assertTrue(
            receiver.requests > 0,
            "the record never left the process: tracy's delivery either was not installed or was not stopped",
        )
    }
}
