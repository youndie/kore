package io.github.youndie.kore.booblik

import io.github.youndie.booblik.net.client.Producer
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The JVM adapter, over `io.github.youndie.booblik.net.client`.
 *
 * **This is the client the flush is for.** Its `close()` completes the accumulated batches
 * exceptionally with `ConnectionClosedException` rather than sending them, so a producer closed
 * without a flush loses up to a linger window of records — research §1.8, filed upstream as
 * [youndie/booblik#68](https://github.com/youndie/booblik/issues/68).
 */
public fun booblikParticipant(
    name: String,
    producer: Producer,
    flushGrace: Duration = 3.seconds,
): ShutdownParticipant =
    booblikParticipant(
        name,
        object : FlushThenClose {
            override suspend fun flush(): Unit = producer.flush()

            override fun close(): Unit = producer.close()
        },
        flushGrace,
    )
