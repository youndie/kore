package io.github.youndie.kore.ktor

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigProblem
import io.github.youndie.kore.config.Configuration
import io.github.youndie.kore.config.ConfigurationException

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
 * The bind is the one CIO makes — the same address resolution, the same `SO_REUSEADDR` — so it fails
 * where the engine would, **provided [reuseAddress] is what the engine was given**. Pass the one value
 * to both.
 *
 * ## Why the default is `true`, which is not CIO's (B-62)
 *
 * CIO defaults `reuseAddress` to `false`, and the two targets make that mean different things. On
 * Kotlin/Native `ktor-network` writes the `0` into the socket; on the JVM it leaves the channel as the
 * JDK made it, and the JDK makes every server channel with `SO_REUSEADDR` on. So a process restarted
 * in place while its old connections sit in `TIME_WAIT` binds on the JVM and fails on native — with
 * the abort above. Measured on Linux 6.6, a fresh bind over a server-side `TIME_WAIT`:
 *
 * | the old listener had the flag | the new bind has it | result |
 * |---|---|---|
 * | no | either | refused |
 * | yes | no | refused |
 * | yes | yes | **binds** |
 *
 * So the flag has to be on in **both** processes, and the first restart after turning it on still
 * meets the old process's `TIME_WAIT`. A port something is *listening* on was refused in every
 * combination, flag or not — the refusal this check exists for does not weaken.
 *
 * Of the two mismatches, `true` here with `false` on the engine lets the check pass over a
 * `TIME_WAIT` the engine then cannot bind — the abort again. `false` here with `true` on the engine
 * refuses every such restart — the failure the engine flag was set to remove. kore recommends `true`
 * on both, so the default is what a consumer following that passes anyway.
 */
public fun Configuration.requireListenable(
    port: ConfigKey<Int>,
    host: String = "0.0.0.0",
    reuseAddress: Boolean = true,
) {
    val value = this[port]
    val reason = listenProblem(host, value, reuseAddress) ?: return
    throw ConfigurationException(prefix, listOf(ConfigProblem(variableOf(port), "$value cannot be listened on: $reason")))
}

/**
 * Why [host]:[port] cannot be listened on, or `null` when it can — and when it returns, the socket
 * is **closed**, not scheduled to close. The engine binds the same port next, so a close that lands
 * later is a check that makes the failure it was written to prevent.
 *
 * That is why this is per platform. `ktor-network`'s native server socket does not close its
 * descriptor in `close()`: it queues it for the selector thread (`TCPServerSocketNative.close` →
 * `notifyClosed`), and the port stays bound until that thread gets to it. CI caught it as a flaky
 * `the check leaves the port free for the engine` on linuxX64. The JVM's `close()` is the channel's,
 * and synchronous.
 */
internal expect fun listenProblem(
    host: String,
    port: Int,
    reuseAddress: Boolean = true,
): String?
