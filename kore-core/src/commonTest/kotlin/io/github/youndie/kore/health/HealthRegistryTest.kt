package io.github.youndie.kore.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

private class Scripted(
    override val name: String,
    override val timeout: Duration = 1.seconds,
    private val takes: Duration = Duration.ZERO,
    private var failWith: Throwable? = null,
    private val hang: Boolean = false,
) : HealthCheck {
    var runs: Int = 0
        private set

    fun startFailing(failure: Throwable) {
        failWith = failure
    }

    fun startPassing() {
        failWith = null
    }

    override suspend fun check() {
        runs++
        if (hang) awaitCancellation()
        delay(takes)
        failWith?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class HealthRegistryTest {
    @Test
    fun `a check that has never run is not healthy`() = runTest {
        val registry = HealthRegistry(listOf(Scripted("store")), timeSource = testScheduler.timeSource)

        val result = registry.snapshot().single()

        // Not UNHEALTHY and emphatically not HEALTHY: a process whose first refresh has not finished
        // has not earned a 200, and saying so as UNKNOWN keeps "we do not know" distinguishable from
        // "we asked and it said no".
        assertEquals(HealthStatus.UNKNOWN, result.status)
        assertFalse(registry.allHealthy())
    }

    @Test
    fun `a check that answers is healthy and carries its age`() = runTest {
        val registry = HealthRegistry(listOf(Scripted("store")), timeSource = testScheduler.timeSource)

        registry.refreshOnce()

        val result = registry.snapshot().single()
        assertEquals(HealthStatus.HEALTHY, result.status)
        assertTrue(result.age != null, "a healthy result carried no age")
        assertTrue(registry.allHealthy())
    }

    @Test
    fun `a failing check names its reason`() = runTest {
        val store = Scripted("store")
        val registry = HealthRegistry(listOf(store), timeSource = testScheduler.timeSource)
        store.startFailing(IllegalStateException("connection refused"))

        registry.refreshOnce()

        val result = registry.snapshot().single()
        assertEquals(HealthStatus.UNHEALTHY, result.status)
        assertEquals("connection refused", result.message)
    }

    @Test
    fun `a check that hangs is unhealthy rather than a probe that hangs`() = runTest {
        val registry =
            HealthRegistry(
                listOf(Scripted("store", timeout = 1.seconds, hang = true)),
                timeSource = testScheduler.timeSource,
            )

        registry.refreshOnce()

        val result = registry.snapshot().single()
        assertEquals(HealthStatus.UNHEALTHY, result.status)
        assertTrue(result.message!!.contains("did not answer"), "the reason did not say it timed out")
    }

    /**
     * The one that matters most.
     *
     * A cache that keeps returning the last good value is a probe that reports health straight
     * through an outage — which is the failure this feature exists to end, not a corner case.
     */
    @Test
    fun `a result past its budget stops counting as an answer`() = runTest {
        val registry =
            HealthRegistry(
                listOf(Scripted("store")),
                staleAfter = 10.seconds,
                timeSource = testScheduler.timeSource,
            )
        registry.refreshOnce()
        assertTrue(registry.allHealthy(), "the fresh result was not healthy")

        delay(11.seconds)

        val result = registry.snapshot().single()
        assertEquals(HealthStatus.UNKNOWN, result.status, "a stale result was still being served as healthy")
        assertFalse(registry.allHealthy())
        assertTrue(result.message!!.contains("past the"), "the reason did not say it was stale")
    }

    @Test
    fun `one unhealthy check is enough`() = runTest {
        val bad = Scripted("broker")
        val registry = HealthRegistry(listOf(Scripted("store"), bad), timeSource = testScheduler.timeSource)
        bad.startFailing(IllegalStateException("no route to host"))

        registry.refreshOnce()

        assertFalse(registry.allHealthy())
        assertEquals(listOf(HealthStatus.HEALTHY, HealthStatus.UNHEALTHY), registry.snapshot().map { it.status })
    }

    @Test
    fun `a check that recovers becomes healthy again`() = runTest {
        val store = Scripted("store")
        val registry = HealthRegistry(listOf(store), timeSource = testScheduler.timeSource)
        store.startFailing(IllegalStateException("down"))
        registry.refreshOnce()
        assertFalse(registry.allHealthy())

        store.startPassing()
        registry.refreshOnce()

        assertTrue(registry.allHealthy(), "the registry latched a failure that had recovered")
    }

    @Test
    fun `one slow check does not stop the others being asked`() = runTest {
        val slow = Scripted("slow", timeout = 1.seconds, hang = true)
        val fast = Scripted("fast")
        val registry = HealthRegistry(listOf(slow, fast), timeSource = testScheduler.timeSource)

        registry.refreshOnce()

        assertEquals(1, fast.runs, "the check after a hanging one was never asked")
    }
}
