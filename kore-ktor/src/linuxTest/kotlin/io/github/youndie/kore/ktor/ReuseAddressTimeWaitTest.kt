package io.github.youndie.kore.ktor

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.Environment
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * B-62: a restart in place binds over its own `TIME_WAIT` only when **both** processes set
 * `SO_REUSEADDR`, and [listenProblem] answers the way the engine's bind would.
 *
 * Each case builds a real server-side `TIME_WAIT` on a fresh port: a listener with or without the
 * flag accepts one connection, the **server** closes first — which is what a stopping server does to
 * a keep-alive client — then the client, then the listener. Linux only, because the table is Linux's:
 * measured on 6.6 with the same sequence in Python before this was written.
 */
@OptIn(ExperimentalForeignApi::class)
class ReuseAddressTimeWaitTest {
    @Test
    fun `a restart binds over its own TIME_WAIT when both processes set the flag`() {
        assertEquals(null, listenProblem("0.0.0.0", timeWaitLeftBy(reuseAddress = true), reuseAddress = true))
    }

    /** The control: without it the case above passes on a port with no `TIME_WAIT` at all. */
    @Test
    fun `the same TIME_WAIT refuses a bind without the flag`() {
        assertNotNull(listenProblem("0.0.0.0", timeWaitLeftBy(reuseAddress = true), reuseAddress = false))
    }

    /**
     * The cost the flag cannot remove: the kernel checks the flag the **old** socket had, so the first
     * restart after turning it on meets `TIME_WAIT` from a process that did not have it.
     */
    @Test
    fun `the first restart after turning the flag on still meets the old process's TIME_WAIT`() {
        assertNotNull(listenProblem("0.0.0.0", timeWaitLeftBy(reuseAddress = false), reuseAddress = true))
    }

    /**
     * The **default** is the decision B-62 made, so it gets a case of its own: every case above passes
     * the flag explicitly and would stay green if the default went back to CIO's `false`.
     */
    @Test
    fun `requireListenable binds over the TIME_WAIT by default`() {
        val port = timeWaitLeftBy(reuseAddress = true)
        val key = ConfigKey.int("PORT", default = 0)

        ConfigSchema(prefix = "SVC", keys = listOf(key)).read(Environment.of(mapOf("SVC_PORT" to "$port"))).requireListenable(key)
    }

    /** Leaves a server-side `TIME_WAIT` on a fresh port, from a listener with [reuseAddress]; returns the port. */
    private fun timeWaitLeftBy(reuseAddress: Boolean): Int =
        memScoped {
            val listener = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "socket" } }
            val flag = alloc<IntVar>().apply { value = if (reuseAddress) 1 else 0 }
            check(setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, flag.ptr, sizeOf<IntVar>().convert()) == 0) { "setsockopt" }
            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.convert()
            check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind" }
            check(listen(listener, 1) == 0) { "listen" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0) { "getsockname" }

            // Loopback, in network byte order: 127.0.0.1 read as a little-endian integer.
            address.sin_addr.s_addr = LOOPBACK
            val client = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "socket" } }
            check(connect(client, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "connect" }
            val served = accept(listener, null, null).also { check(it >= 0) { "accept" } }

            close(served) // the server first, so the TIME_WAIT is on the server's side of the port
            usleep(SETTLE_MICROS)
            close(client)
            usleep(SETTLE_MICROS)
            close(listener)

            val raw = address.sin_port.toInt()
            ((raw and 0xff) shl 8) or (raw shr 8)
        }

    private companion object {
        const val LOOPBACK = 0x0100007fu
        const val SETTLE_MICROS = 50_000u
    }
}
