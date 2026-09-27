package io.github.youndie.kore.ktor

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.Configuration
import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.Environment
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B-59: a port somebody else holds is a refusal naming its variable, and a free one is no refusal.
 *
 * `runBlocking`, not `runTest`: what is awaited is a socket, and a virtual clock has nothing to say
 * about one. Free ports come from [freePort], never from a `ktor-network` bind: on Kotlin/Native that
 * socket's `close()` releases the port later, on the selector thread, and a test that borrows it
 * inherits the race it is checking for.
 */
class ListenCheckTest {
    private val portKey = ConfigKey.int("PORT", default = 0)

    private fun configured(port: Int): Configuration =
        ConfigSchema(prefix = "SVC", keys = listOf(portKey)).read(Environment.of(mapOf("SVC_PORT" to "$port")))

    @Test
    fun `a port another socket holds refuses the start and names its variable`() =
        runBlocking {
            SelectorManager().use { selector ->
                aSocket(selector).tcp().bind("0.0.0.0", 0).use { holder ->
                    val port = (holder.localAddress as InetSocketAddress).port

                    val refusal = assertFailsWith<ConfigurationException> { configured(port).requireListenable(portKey) }

                    assertEquals("SVC", refusal.prefix)
                    assertEquals(listOf("SVC_PORT"), refusal.problems.map { it.variable })
                    assertTrue(
                        refusal.problems.single().message.startsWith("$port cannot be listened on: "),
                        "the refusal did not name the port: ${refusal.message}",
                    )
                }
            }
        }

    /**
     * B-62 turned the check's `SO_REUSEADDR` on by default, and that must not weaken the refusal above:
     * the flag lets a bind share a port with `TIME_WAIT`, never with a listener. Both sides set it here,
     * which is the case that would give if anything did.
     */
    @Test
    fun `a port another socket listens on is refused with the flag on both sides`() =
        runBlocking {
            SelectorManager().use { selector ->
                aSocket(selector).tcp().bind("0.0.0.0", 0) { reuseAddress = true }.use { holder ->
                    val port = (holder.localAddress as InetSocketAddress).port

                    assertFailsWith<ConfigurationException> { configured(port).requireListenable(portKey, reuseAddress = true) }
                }
            }
        }

    /** The positive control of the test above. */
    @Test
    fun `a free port is no refusal`() {
        configured(freePort()).requireListenable(portKey)
    }

    /**
     * The engine binds the port straight after the check, the way CIO does, and it must get it — every
     * time, not usually. A close that lands on another thread passed this once in several runs on one
     * iteration, so it is many iterations.
     */
    @Test
    fun `the engine can bind the port the moment the check returns`() =
        runBlocking {
            SelectorManager().use { selector ->
                repeat(ENGINE_BINDS) { attempt ->
                    val port = freePort()
                    configured(port).requireListenable(portKey)

                    val engine =
                        runCatching { aSocket(selector).tcp().bind("0.0.0.0", port) }
                            .getOrElse { throw AssertionError("attempt $attempt: the port was still held after the check: $it", it) }
                    engine.close()
                }
            }
        }

    private companion object {
        const val ENGINE_BINDS = 50
    }
}
