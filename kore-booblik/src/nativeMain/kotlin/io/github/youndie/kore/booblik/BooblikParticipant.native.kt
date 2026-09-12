package io.github.youndie.kore.booblik

import io.github.youndie.booblik.native.Producer
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Kotlin/Native adapter, over `io.github.youndie.booblik.native`.
 *
 * **This client already sends on close** — its drain begins with `sendAll()`, under a comment reading
 * *"dropping it would be silent loss"*. kore flushes anyway, and that is deliberate rather than
 * redundant: the two clients disagree about what `close()` means, and a service reading kore's
 * contract should get the same guarantee on both platforms without reading either client's source.
 * If [youndie/booblik#68](https://github.com/youndie/booblik/issues/68) is resolved the other way
 * round, nothing here changes.
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
