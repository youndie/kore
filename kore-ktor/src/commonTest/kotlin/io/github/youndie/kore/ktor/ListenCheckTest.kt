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
 * about one.
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

    /** The positive control of the test above: the same port, released, is no refusal. */
    @Test
    fun `a free port is no refusal`() {
        val port =
            runBlocking {
                SelectorManager().use { selector ->
                    aSocket(selector).tcp().bind("0.0.0.0", 0).use { (it.localAddress as InetSocketAddress).port }
                }
            }

        configured(port).requireListenable(portKey)
    }

    /** The check must not keep what it asked for: the engine binds the same port straight after it. */
    @Test
    fun `the check leaves the port free for the engine`() {
        val port =
            runBlocking {
                SelectorManager().use { selector ->
                    aSocket(selector).tcp().bind("0.0.0.0", 0).use { (it.localAddress as InetSocketAddress).port }
                }
            }

        configured(port).requireListenable(portKey)

        assertEquals(null, listenProblem("0.0.0.0", port))
    }
}
