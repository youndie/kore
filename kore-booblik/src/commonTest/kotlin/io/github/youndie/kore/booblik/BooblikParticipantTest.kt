package io.github.youndie.kore.booblik

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The order is the contract, so the order is what is asserted.
 *
 * A recording double rather than either real client, and that is the honest boundary: what kore
 * promises is *flush with a deadline, then close, in that order*. Whether a flush puts records on a
 * socket is booblik's promise, and it differs between the two clients — which is the whole reason
 * this adapter exists rather than a reason to test booblik here.
 */
private class Recording(
    private val flushFails: Throwable? = null,
    private val flushHangs: Boolean = false,
) : FlushThenClose {
    val calls = mutableListOf<String>()
    val started = CompletableDeferred<Unit>()

    override suspend fun flush() {
        calls += "flush"
        started.complete(Unit)
        flushFails?.let { throw it }
        if (flushHangs) kotlinx.coroutines.awaitCancellation()
    }

    override fun close() {
        calls += "close"
    }
}

class BooblikParticipantTest {
    @Test
    fun `the flush happens before the close`() = runTest {
        val producer = Recording()

        booblikParticipant("orders broker", producer).stop()

        assertEquals(listOf("flush", "close"), producer.calls)
    }

    /**
     * The case the whole adapter exists for, stated as an assertion: closing without flushing is
     * what the JVM client turns into lost records, so a participant that closed first would be
     * exactly the defect it was written to remove.
     */
    @Test
    fun `the close never comes first`() = runTest {
        val producer = Recording()

        booblikParticipant("orders broker", producer).stop()

        assertTrue(producer.calls.indexOf("flush") < producer.calls.indexOf("close"))
    }

    /**
     * A flush against a broker that has gone away must not spend the release budget: the consumer
     * group runs **before** the pools it uses, so holding it open delays everything after it — and
     * the records are lost at that point either way.
     */
    @Test
    fun `a flush that never returns is bounded and the producer is still closed`() = runTest {
        val producer = Recording(flushHangs = true)

        booblikParticipant("orders broker", producer, flushGrace = 50.milliseconds).stop()

        assertEquals(listOf("flush", "close"), producer.calls, "the socket was left open after a flush that hung")
    }

    /**
     * `finally`, not a `catch`: the failure belongs to the stage machine, which records it and runs
     * the next group. What must not happen is a socket left open because a flush failed — one problem
     * turned into two.
     */
    @Test
    fun `a flush that throws still closes and the failure reaches the caller`() = runTest {
        val producer = Recording(flushFails = IllegalStateException("broker is gone"))

        val failure =
            assertFailsWith<IllegalStateException> {
                booblikParticipant("orders broker", producer).stop()
            }

        assertEquals("broker is gone", failure.message)
        assertEquals(listOf("flush", "close"), producer.calls, "the producer was not closed after a failing flush")
    }

    @Test
    fun `the participant carries the name it was given`() = runTest {
        assertEquals("orders broker", booblikParticipant("orders broker", Recording(), 1.seconds).name)
    }
}
