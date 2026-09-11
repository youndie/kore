package io.github.youndie.kore.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

private class Always(
    override val name: String,
    override val timeout: Duration = 1.seconds,
    private val failure: Throwable? = null,
) : HealthCheck {
    override suspend fun check() {
        failure?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ReadinessGateTest {
    @Test
    fun `a service with no checks is ready until it begins stopping`() = runTest {
        val gate = ReadinessGate()

        assertTrue(gate.verdict().ready)

        gate.beginShutdown()

        // No checks is a legitimate answer, not a placeholder: readiness is then purely about
        // whether the process is stopping.
        assertFalse(gate.verdict().ready)
        assertEquals(listOf("shutting down"), gate.verdict().reasons)
    }

    @Test
    fun `a process whose checks have not run yet is not ready`() = runTest {
        val registry = HealthRegistry(listOf(Always("store")), timeSource = testScheduler.timeSource)

        assertFalse(ReadinessGate(registry).verdict().ready, "a process earned a 200 before asking anything")
    }

    @Test
    fun `a failing check keeps the gate shut and names itself`() = runTest {
        val registry =
            HealthRegistry(
                listOf(Always("store"), Always("broker", failure = IllegalStateException("no route to host"))),
                timeSource = testScheduler.timeSource,
            )
        registry.refreshOnce()

        val verdict = ReadinessGate(registry).verdict()

        assertFalse(verdict.ready)
        assertEquals(1, verdict.reasons.size, "a healthy check contributed a reason")
        assertTrue(verdict.reasons.single().contains("broker"), "the reason did not name the failing check")
        assertTrue(verdict.reasons.single().contains("no route to host"), "the reason did not say why")
    }

    /**
     * The two inputs fail for different reasons, and a probe that could not tell them apart would
     * report a draining pod and a broken database identically.
     */
    @Test
    fun `stopping and a broken dependency are separate reasons`() = runTest {
        val registry =
            HealthRegistry(
                listOf(Always("store", failure = IllegalStateException("down"))),
                timeSource = testScheduler.timeSource,
            )
        registry.refreshOnce()
        val gate = ReadinessGate(registry)
        gate.beginShutdown()

        val reasons = gate.verdict().reasons

        assertEquals(2, reasons.size, "the two reasons were collapsed into one")
        assertTrue(reasons.any { it == "shutting down" })
        assertTrue(reasons.any { it.contains("store") })
    }

    @Test
    fun `the latch is one-way`() = runTest {
        val gate = ReadinessGate()
        gate.beginShutdown()
        gate.beginShutdown()

        // A gate that could go back to ready would let a process halfway through closing its pools
        // advertise itself to a load balancer.
        assertTrue(gate.isShuttingDown)
        assertFalse(gate.verdict().ready)
    }
}
