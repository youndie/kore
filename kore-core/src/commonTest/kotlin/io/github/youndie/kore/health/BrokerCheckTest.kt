package io.github.youndie.kore.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * A broker client whose peer has closed the connection, reproduced from research §1.15.
 *
 * Every part of this double is a behaviour with an address. The client dials **once**, in its
 * initializer, and does not keep the address — so it cannot re-dial, and the object stays alive and
 * connected-looking for as long as anyone holds it. A peer close surfaces as
 * `EOFException("broker closed the connection")` on the reader and is re-raised to callers. `metadata`
 * is a request the broker serves; a topic it does not know fails the whole request.
 *
 * The point is not that a broken thing reports broken. It is that **the same object in the same
 * state** answers "are you connected" and "is the broker there" differently.
 */
private class ClientWhosePeerClosed {
    var peerClosed: Boolean = false
    var unknownTopic: Boolean = false
    var neverAnswers: Boolean = false

    /**
     * What a connect-shaped or object-liveness check reads. Stays `true` after the peer closes,
     * because nothing asked the far end anything — which is the whole finding.
     */
    val looksConnected: Boolean get() = true

    suspend fun metadata(topic: String) {
        if (neverAnswers) delay(1.seconds * 60)
        if (peerClosed) throw IllegalStateException("broker closed the connection")
        if (unknownTopic) throw IllegalStateException("UNKNOWN_TOPIC_OR_PARTITION: $topic")
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class BrokerCheckTest {
    private fun registry(client: ClientWhosePeerClosed, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        HealthRegistry(
            listOf(brokerCheck("orders broker", "orders.v2") { topic -> client.metadata(topic) }),
            timeSource = scheduler.timeSource,
        )

    @Test
    fun `a metadata check passes while the broker answers and fails once the peer closes`() = runTest {
        val client = ClientWhosePeerClosed()
        val registry = registry(client, testScheduler)

        registry.refreshOnce()
        assertTrue(registry.allHealthy(), "the check failed while the broker was fine")

        client.peerClosed = true
        registry.refreshOnce()

        assertEquals(HealthStatus.UNHEALTHY, registry.snapshot().single().status)
        assertTrue(
            registry.snapshot().single().message!!.contains("broker closed the connection"),
            "the reason did not carry the client's own words",
        )
    }

    /**
     * The comparison the rule exists for, against the **same** client in the **same** state.
     *
     * A connect-shaped check is not a weaker check — it answers a different question, and the
     * difference is invisible until the peer has gone. It is also the check that is deployed today.
     */
    @Test
    fun `a connect-shaped check passes where a metadata-shaped one fails`() = runTest {
        val client = ClientWhosePeerClosed()
        client.peerClosed = true

        val connectShaped =
            HealthRegistry(
                listOf(storeCheck("socket") { check(client.looksConnected) { "not connected" } }),
                timeSource = testScheduler.timeSource,
            )
        val metadataShaped = registry(client, testScheduler)
        connectShaped.refreshOnce()
        metadataShaped.refreshOnce()

        assertTrue(connectShaped.allHealthy(), "the connect-shaped check no longer demonstrates the trap")
        assertTrue(!metadataShaped.allHealthy(), "the metadata-shaped check did not catch a closed peer")
    }

    /**
     * Naming a real topic is the check rather than a decoration: the broker fails the whole request
     * for a topic it does not know, so a misspelled or migrated topic is caught by the same call.
     */
    @Test
    fun `a topic the broker does not know fails the check`() = runTest {
        val client = ClientWhosePeerClosed()
        client.unknownTopic = true
        val registry = registry(client, testScheduler)

        registry.refreshOnce()

        assertEquals(HealthStatus.UNHEALTHY, registry.snapshot().single().status)
        assertTrue(registry.snapshot().single().message!!.contains("orders.v2"), "the reason did not name the topic")
    }

    /** The failing name carries the topic, because paging on one and looking at the other is the job. */
    @Test
    fun `the check names the broker and the topic it asked about`() = runTest {
        val registry = registry(ClientWhosePeerClosed(), testScheduler)

        registry.refreshOnce()

        assertEquals("orders broker (orders.v2)", registry.snapshot().single().name)
    }

    /** A broker that accepts the request and never answers is unhealthy at the deadline, not a hang. */
    @Test
    fun `a broker that never answers fails within the check timeout`() = runTest {
        val client = ClientWhosePeerClosed()
        client.neverAnswers = true
        val registry = registry(client, testScheduler)

        registry.refreshOnce()

        val result = registry.snapshot().single()
        assertEquals(HealthStatus.UNHEALTHY, result.status)
        assertTrue(result.message!!.contains("did not answer"), "the reason was not the deadline: ${result.message}")
    }
}
