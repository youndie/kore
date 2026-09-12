package io.github.youndie.kore.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
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

    /**
     * The forgotten call, at the registry. A service that registers its checks and never starts the
     * loop is `503` for the life of the process, and the body used to describe a startup race — the
     * one reading that would wait for it to pass. B-41.
     */
    @Test
    fun `a registry nobody started says that nothing will ever run its checks`() = runTest {
        val registry = HealthRegistry(listOf(Scripted("store")), timeSource = testScheduler.timeSource)

        val result = registry.snapshot().single()

        assertEquals(HealthStatus.UNKNOWN, result.status)
        assertTrue(
            result.message!!.contains("HealthRegistry.start"),
            "the message did not name the call that was missing: ${result.message}",
        )
    }

    /**
     * The other half, and the reason this is not simply a better sentence: a registry that *was*
     * started and has not finished its first pass is a real startup race, and must not be reported as
     * a missing call.
     */
    @Test
    fun `a started registry that has not finished its first pass says so instead`() = runTest {
        val registry =
            HealthRegistry(
                listOf(Scripted("store", hang = true)),
                timeSource = testScheduler.timeSource,
            )
        registry.start(this, EmptyCoroutineContext)

        val result = registry.snapshot().single()

        registry.stop()
        assertEquals(HealthStatus.UNKNOWN, result.status)
        assertEquals("has not run yet", result.message)
    }

    /** Stopping is not un-starting: a registry stopped during a shutdown was started. */
    @Test
    fun `a registry that was started and then stopped does not claim it was never started`() = runTest {
        val registry =
            HealthRegistry(
                listOf(Scripted("store", hang = true)),
                timeSource = testScheduler.timeSource,
            )
        registry.start(this, EmptyCoroutineContext)
        registry.stop()

        val result = registry.snapshot().single()

        assertEquals("has not run yet", result.message)
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
