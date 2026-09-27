package io.github.youndie.kore.ktor

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Through `ktor-network`, the library CIO binds with on the JVM, so the NIO defaults are the engine's
 * too. Its `close()` closes the channel before it returns
 * (`ktor-network-jvm-3.6.0-sources.jar!/jvmMain/io/ktor/network/sockets/ServerSocketImpl.kt`), which
 * the native half cannot say — see the `expect`.
 */
internal actual fun listenProblem(
    host: String,
    port: Int,
    reuseAddress: Boolean,
): String? =
    runBlocking {
        try {
            SelectorManager().use { selector ->
                aSocket(selector).tcp().bind(host, port) { this.reuseAddress = reuseAddress }.close()
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure.message ?: failure::class.simpleName ?: "unknown failure"
        }
    }
