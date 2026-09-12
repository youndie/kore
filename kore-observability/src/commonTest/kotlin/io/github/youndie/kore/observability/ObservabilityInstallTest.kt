package io.github.youndie.kore.observability

import io.github.youndie.kore.lifecycle.KoreStage
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.shutdownSequence
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun settings(
    service: String = "orders",
    release: String? = "1.4.0+abc123",
    tracy: AgentEndpoint? = null,
) = ObservabilitySettings(service = service, release = release, instance = "pod-1", tracy = tracy)

class ObservabilityInstallTest {
    /** Rule 2 again, at the wiring rather than at the schema: off is a deployment, not a defect. */
    @Test
    fun `no agent configured installs and has nothing to stop`() = testApplication {
        application {
            val observability = installKoreObservability(settings(release = null))

            assertEquals("observability", observability.name)
        }
        // Reaching here at all is the assertion: an install that threw would fail the application.
        client.get("/")
    }

    @Test
    fun `an agent without a release refuses the start and says what the default would be`() = testApplication {
        application {
            val refusal =
                assertFailsWith<IllegalArgumentException> {
                    installKoreObservability(settings(release = null, tracy = AgentEndpoint("http://127.0.0.1:1", "k")))
                }

            assertTrue(refusal.message!!.contains("release"), "the refusal did not name what was missing")
            assertTrue(
                refusal.message!!.contains("Unspecified"),
                "the refusal did not say what katcher would have called the crash group: ${refusal.message}",
            )
        }
        client.get("/")
    }

    /**
     * Rule 3. The shape is all that can be checked — nothing registers a service name, so a typo
     * creates a phantom that looks healthy and receives nothing — but whitespace is the one shape
     * that breaks matching everywhere downstream.
     */
    @Test
    fun `a service name with whitespace is refused`() = testApplication {
        application {
            val refusal = assertFailsWith<IllegalArgumentException> { installKoreObservability(settings(service = "orders api")) }

            assertTrue(refusal.message!!.contains("orders api"), "the refusal did not quote the name")
        }
        client.get("/")
    }

    @Test
    fun `a blank service name is refused`() = testApplication {
        application {
            assertFailsWith<IllegalArgumentException> { installKoreObservability(settings(service = "  ")) }
        }
        client.get("/")
    }

    /**
     * The scenario the telemetry deadline exists for: tracy configured to an address that does not
     * answer must not hold the shutdown open.
     *
     * Real time and a real socket, because the thing under test is a flush that cannot complete.
     * `127.0.0.1:1` is chosen rather than an unroutable address on purpose — a connection refused
     * comes back immediately, while a black hole would make this test pass by timing out somewhere
     * else. If the flush ever *did* block, the stage's deadline is what this asserts.
     */
    @Test
    fun `an unreachable tracy endpoint does not delay the exit past the telemetry deadline`() = testApplication {
        application {
            val observability =
                installKoreObservability(
                    settings(tracy = AgentEndpoint("http://127.0.0.1:1", "key")),
                    flushGrace = 5.seconds,
                )

            val transcript =
                shutdownSequence(
                    ShutdownDeadlines(
                        preDrainWait = 10.milliseconds,
                        drain = 50.milliseconds,
                        releaseGroup = 200.milliseconds,
                    ),
                ) { telemetry(observability) }.run()

            val telemetry = transcript.stages.single { it.stage == KoreStage.RELEASE_TELEMETRY }
            assertTrue(
                telemetry.took < 1.seconds,
                "the telemetry stage took ${telemetry.took} against a 200ms deadline — an unreachable " +
                    "endpoint is holding the shutdown open",
            )
        }
        client.get("/")
    }
}
