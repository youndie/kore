package io.github.youndie.kore.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * A pool whose server has gone away, reproduced.
 *
 * Its `acquire` succeeds from the idle set — the object is there and nothing has asked the far end
 * anything — and its statement fails. That is not a convenience for the test: it is precisely what a
 * connection pool does when the database behind it is replaced, which is the case research §1.9 says
 * an `acquire`-shaped check reports healthy through.
 */
private class PoolWithADeadServer {
    var serverGone: Boolean = false

    /** Succeeds whether or not the far end exists. A handle is not an answer. */
    fun acquire(): Any = Any()

    suspend fun statement() {
        if (serverGone) throw IllegalStateException("connection reset by peer")
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PooledStoreCheckTest {
    @Test
    fun `a statement check fails when the server behind the pool is gone`() = runTest {
        val pool = PoolWithADeadServer()
        val registry =
            HealthRegistry(listOf(storeCheck("store") { pool.statement() }), timeSource = testScheduler.timeSource)

        registry.refreshOnce()
        assertTrue(registry.allHealthy(), "the check failed while the store was fine")

        pool.serverGone = true
        registry.refreshOnce()

        assertEquals(HealthStatus.UNHEALTHY, registry.snapshot().single().status)
        assertTrue(
            registry.snapshot().single().message!!.contains("connection reset"),
            "the reason did not carry the driver's own words",
        )
    }

    /**
     * The comparison the rule exists for, against the **same** pool in the **same** state.
     *
     * An acquire-shaped check is not a weaker check — it is a check that answers a different
     * question, and the difference is invisible until the far end is gone.
     */
    @Test
    fun `an acquire-shaped check passes where a statement-shaped one fails`() = runTest {
        val pool = PoolWithADeadServer()
        pool.serverGone = true

        val acquireShaped =
            HealthRegistry(
                listOf(storeCheck("acquire") { pool.acquire() }),
                timeSource = testScheduler.timeSource,
            ).also { it.refreshOnce() }

        val statementShaped =
            HealthRegistry(
                listOf(storeCheck("statement") { pool.statement() }),
                timeSource = testScheduler.timeSource,
            ).also { it.refreshOnce() }

        assertTrue(acquireShaped.allHealthy(), "the acquire-shaped check no longer demonstrates the trap")
        assertTrue(!statementShaped.allHealthy(), "the statement-shaped check did not catch a dead server")
    }

    @Test
    fun `a statement that hangs is bounded by the check's own timeout`() = runTest {
        val registry =
            HealthRegistry(
                listOf(storeCheck("slow") { kotlinx.coroutines.awaitCancellation() }),
                timeSource = testScheduler.timeSource,
            )

        registry.refreshOnce()

        assertEquals(HealthStatus.UNHEALTHY, registry.snapshot().single().status)
        assertTrue(registry.snapshot().single().message!!.contains("did not answer"))
    }
}
