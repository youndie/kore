package io.github.youndie.kore.ktor

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_UNSPEC
import platform.posix.AI_NUMERICSERV
import platform.posix.AI_PASSIVE
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.strerror

/**
 * The bind CIO makes, written out so the close is ours: `ktor-network`'s `tcpBind`
 * (`ktor-network-3.6.0-sources.jar!/posixMain/io/ktor/network/sockets/ConnectUtilsNative.kt`) resolves
 * with `getaddrinfo(AF_UNSPEC, SOCK_STREAM, AI_PASSIVE | AI_NUMERICSERV)`, takes the first address,
 * sets `SO_REUSEADDR` to the option, binds and listens. Everything here matches it except the last
 * step, which is `close(2)` and so has happened by the time this returns.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun listenProblem(
    host: String,
    port: Int,
    reuseAddress: Boolean,
): String? =
    memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        hints.ai_flags = AI_PASSIVE or AI_NUMERICSERV
        val found = alloc<CPointerVar<addrinfo>>()
        val resolved = getaddrinfo(host, port.toString(), hints.ptr, found.ptr)
        if (resolved != 0) return@memScoped "cannot resolve $host: ${gai_strerror(resolved)?.toKString()}"
        try {
            val address = found.value!!.pointed
            val descriptor = socket(address.ai_family, SOCK_STREAM, 0)
            if (descriptor < 0) return@memScoped failure("socket")
            try {
                val flag = alloc<IntVar>().apply { value = if (reuseAddress) 1 else 0 }
                when {
                    setsockopt(descriptor, SOL_SOCKET, SO_REUSEADDR, flag.ptr, sizeOf<IntVar>().convert()) != 0 -> failure("setsockopt")
                    bind(descriptor, address.ai_addr, address.ai_addrlen) != 0 -> failure("bind")
                    listen(descriptor, 1) != 0 -> failure("listen")
                    else -> null
                }
            } finally {
                close(descriptor)
            }
        } finally {
            freeaddrinfo(found.value)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun failure(call: String): String {
    val code = errno
    return "$call: ${strerror(code)?.toKString()} (errno $code)"
}
