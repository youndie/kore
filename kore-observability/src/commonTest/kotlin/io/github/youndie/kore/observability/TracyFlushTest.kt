package io.github.youndie.kore.observability

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
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
 * **The records actually leave the process**, against a real receiver on a real socket.
 *
 * This exists because a mutation survived without it. Installing tracy's plugin and *not* its
 * delivery passed every other test in this module: the plugin is visible from outside and the
 * delivery is not, so asserting the plugin proves only that a buffer was installed. A buffer with
 * nothing to empty it is exactly the failure rule 7 forbids.
 *
 * ## Two real servers and no test host, which is not a style choice
 *
 * The first version nested `testApplication { }` inside `runTest { }` and **hung on Kotlin/Native** —
 * the suite ran for over thirty minutes on CI and on the build box before being killed, while the
 * other two suites in this module finish in eighteen seconds. Two test scopes, one of them driving a
 * real socket, is a deadlock waiting to be found; and `runTest`'s virtual clock has no business in a
 * test whose subject is a network round trip. So: real engines, and the work inside
 * `Dispatchers.Default` where `delay` means what it says.
 */
class TracyFlushTest {
    private class Receiver {
        /** One write, one read, from different threads. A count would need an atomic; this does not. */
        @Volatile
        var sawRequest: Boolean = false
    }

    @Test
    fun `a record written before the stop is delivered by the telemetry stage`() = runTest {
        val receiver = Receiver()
        withContext(Dispatchers.Default) {
            val collector =
                embeddedServer(CIO, port = 0) {
                    routing {
                        // tracy chooses the path; this takes whatever it posts to.
                        post("/{...}") {
                            receiver.sawRequest = true
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
                                tracy = AgentEndpoint("http://127.0.0.1:$port", "key"),
                            ),
                            flushGrace = 5.seconds,
                        )
                }
            subject.start(wait = false)
            subject.engine.resolvedConnectors()

            val wired = observability
            assertTrue(wired != null, "the module never ran")
            val agent = wired.tracy
            assertTrue(agent != null, "tracy was configured and no agent was handed back to log with")
            agent.logger("test").info("a record that must survive the shutdown")

            // The telemetry stage's job, called directly. What the stage adds is the deadline, and
            // that is asserted separately in ObservabilityInstallTest.
            wired.stop()

            subject.stop(0, 0)
            collector.stop(0, 0)
        }

        assertTrue(
            receiver.sawRequest,
            "the record never left the process: tracy's delivery either was not installed or was not stopped",
        )
    }
}
