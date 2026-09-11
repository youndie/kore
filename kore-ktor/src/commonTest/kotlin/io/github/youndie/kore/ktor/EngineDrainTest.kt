package io.github.youndie.kore.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EngineDrainTest {
    private fun server() = embeddedServer(CIO, port = 0) { }

    @Test
    fun `a timeout shorter than the grace period is refused where it is written`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                EngineDrain(server(), grace = 10.seconds, timeout = 5.seconds)
            }

        // The acceptance criterion is that the message names both numbers: the reader has to be able
        // to see which of the two they got wrong without opening the source.
        assertTrue(failure.message!!.contains("10s"), "the message did not name the grace period")
        assertTrue(failure.message!!.contains("5s"), "the message did not name the timeout")
    }

    @Test
    fun `a timeout equal to the grace period is refused too`() {
        // Equal is the interesting boundary: it leaves a hard-kill window of exactly zero, so the
        // engine cancels and then does not wait at all. It looks configured and does nothing.
        assertFailsWith<IllegalArgumentException> {
            EngineDrain(server(), grace = 5.seconds, timeout = 5.seconds)
        }
    }

    @Test
    fun `a negative grace period is refused`() {
        assertFailsWith<IllegalArgumentException> {
            EngineDrain(server(), grace = (-1).seconds, timeout = 5.seconds)
        }
    }

    @Test
    fun `a sane pair is accepted and the participant names itself`() {
        val drain = EngineDrain(server(), grace = 15.seconds, timeout = 20.seconds)

        assertTrue(drain.name.isNotBlank())
    }
}
