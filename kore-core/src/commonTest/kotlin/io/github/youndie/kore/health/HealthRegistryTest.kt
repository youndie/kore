package io.github.youndie.kore.health

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

/**
 * A check inside a call cancellation does not reach.
 *
 * `NonCancellable` rather than a thread that sleeps, because it models the real shape on
 * Kotlin/Native — an FFI call or a blocking driver — while staying on the test dispatcher. Note that
 * `refreshOnce`'s `withTimeoutOrNull` cannot interrupt it either, which is the property under test
 * and is already written down as a quirk of the registry.
 */
private class Gated(
    override val name: String,
    private val gate: CompletableDeferred<Unit>,
    override val timeout: Duration = 1.seconds,
) : HealthCheck {
    var entered: Boolean = false
        private set
    var left: Boolean = false
        private set

    override suspend fun check() {
        entered = true
        withContext(NonCancellable) { gate.await() }
        left = true
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

        registry.stopAndJoin()
        assertEquals(HealthStatus.UNKNOWN, result.status)
        assertEquals("has not run yet", result.message)
    }

    /**
     * The point of [HealthRegistry.stopAndJoin], and the defect that produced it (kore#79).
     *
     * A check is usually a statement against the resource a later stage releases, so "the loop has
     * been told to stop" and "no check is running" are different facts, and only the second one is
     * safe to release on.
     *
     * **The `finally` is not tidiness.** A `NonCancellable` block that nobody releases cannot be
     * cancelled by `runTest` either, so an assertion that throws before the gate is opened hangs the
     * suite instead of failing it — which is how this test behaved the first time it was made to
     * fail on purpose. A guard whose failure mode is a hang is worse than the defect it guards.
     */
    @Test
    fun `stopAndJoin waits for a check that cancellation cannot reach`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val check = Gated("store", gate)
        val registry = HealthRegistry(listOf(check), timeSource = testScheduler.timeSource)
        try {
            registry.start(this, EmptyCoroutineContext)
            runCurrent()
            assertTrue(check.entered, "the check never started, so this test would pass for the wrong reason")

            val stopping = launch { registry.stopAndJoin() }
            runCurrent()
            assertFalse(stopping.isCompleted, "stopAndJoin returned while the check was still inside the store")

            gate.complete(Unit)
            runCurrent()
            assertTrue(stopping.isCompleted)
            assertTrue(check.left)
        } finally {
            gate.complete(Unit)
            runCurrent()
        }
    }

    /** The contrast that makes the method above worth having, rather than an alias. */
    @Suppress("DEPRECATION")
    @Test
    fun `stop returns while a check that cancellation cannot reach is still running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val check = Gated("store", gate)
        val registry = HealthRegistry(listOf(check), timeSource = testScheduler.timeSource)
        try {
            registry.start(this, EmptyCoroutineContext)
            runCurrent()

            registry.stop()

            assertTrue(check.entered)
            assertFalse(check.left, "then `stop` does wait after all, and kore#79 is not what it says")
        } finally {
            // Left running, the check would hold this test open the way it holds a pool open.
            gate.complete(Unit)
            runCurrent()
        }
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
        registry.stopAndJoin()

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
