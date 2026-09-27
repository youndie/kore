package io.github.youndie.kore.ktor

import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.DrainGate
import io.github.youndie.kore.lifecycle.KoreStage
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.shutdownSequence
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * B-61: **the announce serves, and only the drain refuses.**
 *
 * The sample gated the refusal on readiness, which the announce flips — so every request after the
 * signal was answered `503` while the pre-drain wait was supposed to be serving it, and every service
 * built from the sample did the same. The oracle's A5 found it only once the wait outlasted its
 * requests. This asks the question directly, through the path a consumer writes: a real CIO engine,
 * the real sequence, [AnnounceNotReady] and [EngineDrain], and requests on real sockets.
 *
 * `runBlocking`, not `runTest`: the pre-drain wait is real time, and the engine answers on real
 * threads.
 */
class RefusalOpensAtTheDrainTest {
    @Test
    fun `a request during the announce is served and one during the drain is refused`() =
        runBlocking {
            val readiness = ReadinessGate()
            val startup = StartupGate()
            val draining = DrainGate()
            val server =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    installShutdownRefusal(draining)
                    installKoreProbes(startup, readiness)
                    routing { get("/work") { call.respondText("worked\n") } }
                }
            server.startForKore()
            startup.markStarted()
            val port = server.engine.resolvedConnectors().first().port

            SelectorManager(Dispatchers.Default).use { selector ->
                // Opened BEFORE the signal and kept: the engine goes on serving an open connection
                // through the drain (research §1.13), so this is where the drain's refusal is visible.
                val kept = RawHttp(aSocket(selector).tcp().connect("127.0.0.1", port))
                assertEquals(200, kept.get("/work").status, "the service did not serve before the signal")

                val sequence =
                    shutdownSequence(ShutdownDeadlines(preDrainWait = ANNOUNCE, drain = 3.seconds, releaseGroup = 1.seconds)) {
                        announce(AnnounceNotReady(readiness))
                        drain(EngineDrain(server, grace = 2.seconds, timeout = 3.seconds, gate = draining))
                    }
                val run = async(Dispatchers.Default) { sequence.run() }

                awaitTrue("the announce never flipped readiness") { readiness.isShuttingDown }

                // THE ANNOUNCE. Readiness says leave; everything else is still served — on the kept
                // connection and on a new one, because a node that has not heard yet opens new ones.
                assertEquals(503, fresh(selector, port).get(KoreRoutes.READY).status, "readiness did not fall in the announce")
                val onKept = kept.get("/work")
                val onNew = fresh(selector, port).get("/work")
                assertEquals(200, onKept.status, "refused on an open connection during the announce: ${onKept.body}")
                assertEquals(200, onNew.status, "refused on a new connection during the announce: ${onNew.body}")
                // And the two 200s above were asked inside the announce, or they prove nothing.
                assertFalse(draining.isDraining, "the drain had begun before the announce was asked")

                // THE DRAIN. The same kept connection, now refused and told not to come back.
                awaitTrue("the drain never opened the refusal") { draining.isDraining }
                val refused = kept.get("/work")
                assertEquals(503, refused.status, "a request during the drain was served")
                assertEquals("close", refused.headers["connection"], "the refusal did not say Connection: close")
                assertEquals(SHUTTING_DOWN_BODY, refused.body)

                val transcript = withTimeout(10.seconds) { run.await() }
                assertTrue(transcript[KoreStage.DRAIN] != null, "the drain stage did not run: $transcript")
            }
        }

    private suspend fun fresh(selector: SelectorManager, port: Int) = RawHttp(aSocket(selector).tcp().connect("127.0.0.1", port))

    private suspend fun awaitTrue(
        message: String,
        condition: () -> Boolean,
    ) {
        withTimeout(5.seconds) { while (!condition()) delay(5.milliseconds) }
        assertTrue(condition(), message)
    }

    private companion object {
        /** Long enough that the requests above land inside it with a margin nobody's scheduler eats. */
        val ANNOUNCE: Duration = 1500.milliseconds
    }
}

/** One HTTP/1.1 connection, spoken by hand: the test host has no socket, and the engine's behaviour is the subject. */
private class RawHttp(
    socket: Socket,
) {
    private val read = socket.openReadChannel()
    private val write = socket.openWriteChannel(autoFlush = true)

    class Answer(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )

    suspend fun get(path: String): Answer {
        write.writeStringUtf8("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n")
        val statusLine = checkNotNull(read.readLine()) { "the connection closed before a status line" }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = checkNotNull(read.readLine()) { "the connection closed inside the headers" }
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        return Answer(statusLine.split(' ')[1].toInt(), headers, read.readByteArray(length).decodeToString())
    }
}
