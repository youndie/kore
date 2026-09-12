package io.github.youndie.kore.ktor

import io.github.youndie.kore.health.HealthCheck
import io.github.youndie.kore.health.HealthRegistry
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

private class Check(
    override val name: String,
    override val timeout: Duration = 1.seconds,
    private val failure: Throwable? = null,
    private val hang: Boolean = false,
) : HealthCheck {
    override suspend fun check() {
        if (hang) awaitCancellation()
        failure?.let { throw it }
    }
}

/**
 * A clock the test moves by hand.
 *
 * `testApplication` does not hand out its scheduler, and the staleness rule is about a *duration*
 * passing rather than about coroutines being scheduled — so the registry is given a clock this test
 * advances directly. Waiting the real ten seconds would measure the runner and cost ten seconds.
 */
@OptIn(ExperimentalTime::class)
class ProbeRoutesTest {
    private fun started() = StartupGate().apply { markStarted() }

    @Test
    fun `a process whose store is unreachable is not ready but is alive`() = testApplication {
        val registry = HealthRegistry(listOf(Check("store", failure = IllegalStateException("connection refused"))))
        registry.refreshOnce()
        application { installKoreProbes(started(), ReadinessGate(registry)) }

        val ready = client.get(KoreRoutes.READY)

        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertTrue(ready.bodyAsText().contains("store"), "the body did not name the failing check")
        assertTrue(ready.bodyAsText().contains("connection refused"), "the body did not say why")
        // The process is not wedged and restarting it would not help, so liveness must not fail.
        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.LIVE).status)
    }

    @Test
    fun `liveness survives every dependency being down`() = testApplication {
        val registry =
            HealthRegistry(
                listOf(Check("store", failure = IllegalStateException("down")), Check("broker", failure = IllegalStateException("down"))),
            )
        registry.refreshOnce()
        application { installKoreProbes(started(), ReadinessGate(registry)) }

        repeat(5) {
            assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.LIVE).status)
            assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.HEALTH).status)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(KoreRoutes.READY).status)
    }

    @Test
    fun `a check that hangs produces a stale answer rather than a hung probe`() = testApplication {
        val clock = TestTimeSource()
        val registry =
            HealthRegistry(
                listOf(Check("store", timeout = 1.seconds, hang = true)),
                staleAfter = 10.seconds,
                timeSource = clock,
            )
        registry.refreshOnce()
        clock += 11.seconds
        application { installKoreProbes(started(), ReadinessGate(registry)) }

        val ready = client.get(KoreRoutes.READY)

        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertTrue(ready.bodyAsText().contains("store"), "the body did not name the check")
        // The age is the point: a cached healthy answer and a fresh one are different facts.
        assertTrue(ready.bodyAsText().contains("past the"), "the body did not report the result as stale")
    }

    @Test
    fun `startup does not un-latch`() = testApplication {
        val registry = HealthRegistry(listOf(Check("store")))
        registry.refreshOnce()
        val startup = started()
        val readiness = ReadinessGate(registry)
        application { installKoreProbes(startup, readiness) }

        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.STARTUP).status)

        // A dependency fails, and then the process begins stopping. Neither un-latches startup: the
        // kubelet runs that probe only at startup, so a later 503 is a value nothing reads and
        // everything misreads — and it would restart a pod that is trying to stop cleanly.
        HealthRegistry(listOf(Check("store", failure = IllegalStateException("down")))).refreshOnce()
        readiness.beginShutdown()

        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.STARTUP).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(KoreRoutes.READY).status)
    }

    /**
     * The forgotten call as an operator meets it: a stalled rollout and a probe body.
     *
     * The body has to name the missing call, because every other signal in this situation says
     * "starting up" — the pod is running, the process is alive, liveness is `200`, and readiness is
     * `503` with a reason that reads like a race about to resolve. It never resolves. B-41.
     */
    @Test
    fun `a registry nobody started says so in the readiness body`() = testApplication {
        val registry = HealthRegistry(listOf(Check("store")))
        application { installKoreProbes(started(), ReadinessGate(registry)) }

        val ready = client.get(KoreRoutes.READY)

        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertTrue(
            ready.bodyAsText().contains("HealthRegistry.start"),
            "the body did not name the call that was missing: ${ready.bodyAsText()}",
        )
    }

    @Test
    fun `startup says what it is still waiting for`() = testApplication {
        val startup = StartupGate(setOf("migrations", "cache warm"))
        application { installKoreProbes(startup, ReadinessGate()) }

        val before = client.get(KoreRoutes.STARTUP)
        assertEquals(HttpStatusCode.ServiceUnavailable, before.status)
        assertTrue(before.bodyAsText().contains("migrations"), "the body did not name the outstanding gate")

        startup.completed("migrations")
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(KoreRoutes.STARTUP).status)

        startup.completed("cache warm")
        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.STARTUP).status)
    }

    @Test
    fun `the health alias is liveness and not readiness`() = testApplication {
        val registry = HealthRegistry(listOf(Check("store", failure = IllegalStateException("down"))))
        registry.refreshOnce()
        application { installKoreProbes(started(), ReadinessGate(registry)) }

        // The trap this alias exists to survive: a chart pointing readiness here would get a probe
        // that cannot fail while the process is alive.
        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.HEALTH).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(KoreRoutes.READY).status)
    }

    @Test
    fun `a service can declare itself wedged and liveness then fails`() = testApplication {
        val liveness = LivenessGate()
        application { installKoreProbes(started(), ReadinessGate(), liveness) }

        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.LIVE).status)

        liveness.declareWedged("the writer thread has not advanced in 5 minutes")

        val live = client.get(KoreRoutes.LIVE)
        assertEquals(HttpStatusCode.ServiceUnavailable, live.status)
        assertTrue(live.bodyAsText().contains("writer thread"), "the body did not say why a restart is the fix")
    }
}
