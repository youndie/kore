package io.github.youndie.kore.booblik

import io.github.youndie.kore.lifecycle.ShutdownParticipant
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The two verbs a broker producer needs at shutdown, in the order that matters.
 *
 * An interface kore owns rather than either client's type, for two reasons. The clients are concrete
 * classes with different package names, so nothing can be written once against both; and a contract
 * is what a test can drive, which is how the *order* becomes something asserted rather than something
 * read in a diff.
 */
public interface FlushThenClose {
    /** Sends what is accumulated. Suspends until it is sent or the caller stops waiting. */
    public suspend fun flush()

    /** Releases the socket. What it does to anything still queued is the client's business. */
    public fun close()
}

/**
 * A consumer-stage participant: **flush with a deadline, then close.**
 *
 * ## Why this is two verbs and not one
 *
 * `Producer.close()` is `mailbox.close()`, and the loop's `finally` completes every queued record —
 * on the JVM client, **exceptionally**, rather than sending it (research §1.8). So closing a producer
 * without flushing first discards up to a linger window of records, silently, and the records that
 * vanish are exactly the ones nothing was awaiting. The portfolio's most complete service does this
 * today.
 *
 * The native client does the opposite: its drain sends first. kore does not rely on either, because
 * relying on the one that happens to be right is how the difference goes unnoticed — and because
 * which one is right may change when
 * [youndie/booblik#68](https://github.com/youndie/booblik/issues/68) is resolved.
 *
 * ## Why the flush is bounded and the close is not
 *
 * A flush against a broker that has gone away would otherwise spend the whole release budget, and the
 * consumer group runs **before** the pools it uses. Past [flushGrace] kore stops waiting and closes
 * anyway: the records are lost either way at that point, and holding the shutdown open does not make
 * them arrive. `close` is not bounded because it is releasing a socket, not talking to anyone.
 *
 * **The deadline is not a failure.** A flush that overruns is recorded as one by the stage machine
 * only if it throws; timing out here is a decision to stop waiting, and the transcript shows the
 * stage took its full budget.
 */
public fun booblikParticipant(
    name: String,
    producer: FlushThenClose,
    flushGrace: Duration = 3.seconds,
): ShutdownParticipant =
    object : ShutdownParticipant {
        override val name: String = name

        override suspend fun stop() {
            try {
                withTimeoutOrNull(flushGrace) { producer.flush() }
            } finally {
                // ALWAYS, including when the flush threw or the stage cancelled the scope. A
                // participant that leaves a socket open because its flush failed has turned one
                // problem into two.
                producer.close()
            }
        }
    }
