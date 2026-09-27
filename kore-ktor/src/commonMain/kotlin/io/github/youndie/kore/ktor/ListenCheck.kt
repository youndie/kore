package io.github.youndie.kore.ktor

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigProblem
import io.github.youndie.kore.config.Configuration
import io.github.youndie.kore.config.ConfigurationException
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Refuses the start when the port in [port] cannot be listened on, the way a missing variable does.
 *
 * Call it after the configuration is read and before the server starts. It binds [host] and the
 * port once, closes the socket, and on failure throws the [ConfigurationException] a consumer
 * already catches for the rest of the configuration — so a busy port is the same one-line refusal
 * and exit as a missing variable, and it names the variable an operator has to change.
 *
 * **Why it exists: a busy port was not a refusal on Kotlin/Native, it was an abort.** CIO binds
 * inside a coroutine it launches itself, after `start(wait = false)` has returned; the bind failure
 * reaches the coroutine's root with no handler, and on Kotlin/Native that ends the process with
 * `SIGABRT`, exit 134 and some fifty lines of stack. The JVM exits 1 with a stack trace. Neither
 * names the variable. Found by a service built from keel ([keel#49](https://github.com/youndie/keel/issues/49)),
 * and it is Ktor's behaviour rather than kore's — research-upstream-proposals §1.3.
 *
 * **This narrows the problem and does not close it.** Another process can take the port between this
 * bind and the engine's; the common case — something already holds it — becomes a sentence, and the
 * race stays Ktor's.
 *
 * The bind goes through `ktor-network`, the library CIO binds with, so it fails where the engine
 * would. [reuseAddress] is there to match an engine configured with it; CIO's default is `false`.
 */
public fun Configuration.requireListenable(
    port: ConfigKey<Int>,
    host: String = "0.0.0.0",
    reuseAddress: Boolean = false,
) {
    val value = this[port]
    val reason = listenProblem(host, value, reuseAddress) ?: return
    throw ConfigurationException(prefix, listOf(ConfigProblem(variableOf(port), "$value cannot be listened on: $reason")))
}

/** Why [host]:[port] cannot be listened on, or `null` when it can. */
internal fun listenProblem(
    host: String,
    port: Int,
    reuseAddress: Boolean = false,
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
