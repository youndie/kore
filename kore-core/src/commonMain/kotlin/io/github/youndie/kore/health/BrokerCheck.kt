package io.github.youndie.kore.health

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A check that proves the **broker** answered, about a topic this service actually uses.
 *
 * The sibling of [storeCheck], and the same rule in a different dependency: ask the far end a
 * question and wait for its answer, rather than asking a local object about itself. kore takes no
 * broker-client dependency — which client a service uses is its own choice, and for booblik there
 * are currently two with different APIs — so what kore offers is the shape.
 *
 * ## Why "the socket is open" is a different question
 *
 * A TCP connect answers the kernel: something is listening on that address. It does not say the
 * process behind it is the broker, that it has finished starting, that it knows this topic, or that
 * this client's own session is still usable. Those are the states a check exists to catch, and they
 * all present as a healthy socket.
 *
 * The connect-shaped check is also the one that is already deployed — it is what the portfolio's
 * chart asks of its broker today — so the value here is the comparison, not the novelty.
 *
 * ## Name a topic the service uses
 *
 * Metadata for a topic nobody publishes to is a question whose answer can stay right while the
 * service's own traffic is broken — a topic can be absent, misspelled in configuration, or owned by
 * a cluster this process is no longer talking to. The check is worth what the topic in it is worth.
 *
 * @param topic named separately from [name] so it appears in the failure a probe reports: "orders
 *   broker" is what to page about, `orders.v2` is what to look at.
 * @param metadata asks the broker about [topic] and returns when it has answered. It must be a
 *   request the broker serves, not a property of the client object.
 */
public fun brokerCheck(
    name: String,
    topic: String,
    timeout: Duration = 1.seconds,
    metadata: suspend (topic: String) -> Unit,
): HealthCheck =
    object : HealthCheck {
        override val name: String = "$name ($topic)"
        override val timeout: Duration = timeout

        override suspend fun check() {
            metadata(topic)
        }
    }
