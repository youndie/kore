package io.github.youndie.kore.oracle

import io.github.youndie.booblik.Offset
import io.github.youndie.booblik.PartitionId
import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.net.client.BooblikConnection
import io.github.youndie.booblik.net.client.Producer
import io.github.youndie.booblik.net.client.ProducerConfig
import io.github.youndie.booblik.net.wire.ErrorCode
import io.github.youndie.kore.booblik.FlushThenClose
import io.github.youndie.kore.booblik.booblikParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * **Does kore's participant actually save the records a bare `close()` loses?** — B-45.
 *
 * `BooblikParticipantTest` asserts kore's half, the *order*: flush, then close. It cannot assert the
 * other half — that a flush puts the records on a socket and that a shutdown without one drops them —
 * because that is the client's behaviour against a server, and a double reproducing the loss would be
 * asserting the thing it was written to reproduce.
 *
 *     ./gradlew :samples:oracle:brokerFlush
 *
 * **What the control actually is.** `Producer.close()` is not the naive thing it looks like: closing
 * the mailbox breaks the accumulation window and the queued batch goes out (booblik's own
 * `ProducerCloseFlushTest`). But it goes out **on the producer's coroutine**, and `close()` does not
 * wait for it — so a service that closes its producer and then tears the scope and the connection
 * down in the same breath, which is what shutting down *is*, loses the batch anyway. That is the
 * control, and it is the shape of the shutdown every service writes without kore.
 *
 * **Two arms, and the control has to lose records.** A run where both arms keep everything has
 * measured nothing. The control failing is what gives the treatment its meaning.
 */
private const val RECORDS = 50

private const val TOPIC = "kore-b45"

fun main() {
    val control = arm("control — close() then tear down", Shutdown.CLOSE)
    val treatment = arm("treatment — kore's participant", Shutdown.PARTICIPANT)
    val mechanism = arm("mechanism — close(), wait, then tear down", Shutdown.CLOSE_THEN_WAIT)

    println()
    println("records sent without awaiting, per arm: $RECORDS (plus one awaited warm-up)")
    println("  control   — producer.close()            : ${control.readBack} of ${RECORDS + 1} read back")
    println("  treatment — booblikParticipant().stop() : ${treatment.readBack} of ${RECORDS + 1} read back")
    println("  mechanism — producer.close() + ${GRACE_MILLIS} ms   : ${mechanism.readBack} of ${RECORDS + 1} read back")
    println()
    println(
        if (mechanism.readBack == RECORDS + 1) {
            "The batch is not thrown away by close() — it goes out on the producer's own coroutine, " +
                "and what loses it is tearing the scope and the connection down before that coroutine " +
                "has run. kore's contribution is the *waiting*, not the sending."
        } else {
            "close() did not send the batch even when given ${GRACE_MILLIS} ms of quiet, so it drops it " +
                "outright rather than sending it late."
        },
    )
    println()

    val controlLost = control.readBack <= 1
    val treatmentKept = treatment.readBack == RECORDS + 1
    println(
        when {
            controlLost && treatmentKept ->
                "SUPPORTED: tearing down without a flush loses the accumulated batch and kore's " +
                    "participant does not."
            !controlLost ->
                "INCONCLUSIVE: the control kept its records, so this run did not reproduce the loss — " +
                    "the producer's own coroutine won the race with the teardown. Nothing is proved " +
                    "about the treatment either way."
            else ->
                "REFUTED: the control lost its records and the treatment did not keep them. kore's " +
                    "flush is not doing what feature-ordered-shutdown §5 says it does."
        },
    )
    exitProcess(if (controlLost && treatmentKept) 0 else 1)
}

private const val GRACE_MILLIS = 500L

/**
 * The three shutdowns this compares.
 *
 * [CLOSE_THEN_WAIT] is not a shutdown anybody writes; it is there to say *why* [CLOSE] loses the
 * batch. Without it the run shows a difference and lets the reader assume the wrong mechanism — that
 * `close()` throws the records away — which is what this repository's own notes said until this
 * experiment, and what booblik's `Producer.close()` KDoc explicitly denies.
 */
private enum class Shutdown { CLOSE, CLOSE_THEN_WAIT, PARTICIPANT }

private class ArmResult(val readBack: Int)

private fun arm(label: String, shutdown: Shutdown): ArmResult {
    val broker = BrokerContainer(topic = TOPIC)
    return try {
        broker.start()
        runBlocking { drive(broker.port, label, shutdown) }
    } catch (e: Throwable) {
        // The broker's own log is the half of a failure the client cannot see, and the first version
        // of this experiment spent three runs guessing at it.
        println("  !! $label failed: ${e::class.qualifiedName}: ${e.message}")
        println("  broker state: ${broker.state().trim()}")
        println(broker.logs().lines().joinToString("\n") { "  broker| $it" })
        throw e
    } finally {
        broker.remove()
    }
}

private suspend fun drive(port: Int, label: String, shutdown: Shutdown): ArmResult {
    val topic = TopicName(TOPIC)
    val partition = PartitionId(0)
    val scope = CoroutineScope(SupervisorJob())

    val connection = awaitBroker(port, topic, scope)

    // A LONG LINGER, so nothing leaves on its own. With the default 5 ms the records would be on the
    // wire before either arm did anything and both arms would pass — the shape of a run that measures
    // nothing.
    val producer = Producer(connection, scope, ProducerConfig(lingerMillis = 60_000, maxBatchSize = 10_000))

    // One record that IS flushed and IS awaited. Awaited because `Producer.deliver` completes a
    // handle exceptionally rather than throwing, so a broker that refuses the record — an undeclared
    // topic, say — is invisible to a caller who only calls `flush()`. This is the arm's positive
    // control: if the warm-up does not land, nothing below means anything.
    val warmUp = producer.send(topic, partition, "warm-up".encodeToByteArray())
    producer.flush()
    warmUp.await()

    repeat(RECORDS) { i ->
        // NOT awaited: an awaited record is one the caller already knows landed, and those are not
        // the records this is about.
        producer.send(topic, partition, "record-$i".encodeToByteArray())
    }
    delay(200)

    println("$label: $RECORDS records queued inside the linger window")
    when (shutdown) {
        Shutdown.PARTICIPANT ->
            booblikParticipant(
                "broker",
                object : FlushThenClose {
                    override suspend fun flush(): Unit = producer.flush()

                    override fun close(): Unit = producer.close()
                },
                flushGrace = 10.seconds,
            ).stop()

        Shutdown.CLOSE -> producer.close()

        Shutdown.CLOSE_THEN_WAIT -> {
            producer.close()
            delay(GRACE_MILLIS)
        }
    }
    // The teardown, in the same breath as the close — this is the line the control loses its batch to.
    connection.close()
    scope.cancel()

    // A NEW connection: the point is what the broker has, not what the old client thinks it sent.
    val reader = CoroutineScope(SupervisorJob())
    val check = awaitBroker(port, topic, reader)
    val fetched = check.fetch(topic, partition, Offset(0), maxBytes = 4 * 1024 * 1024)
    val count = fetched.records.size
    check.close()
    reader.cancel()
    return ArmResult(count)
}

private const val ATTEMPTS = 60

/**
 * A connection the broker has actually answered on, for the topic the run needs.
 *
 * **An open socket is not readiness here.** `docker run -p` publishes the port before the process
 * inside binds it, so the first connection lands on docker's proxy and dies the moment the client
 * asks it anything — which is an `EOFException` from a socket that was just opened successfully, and
 * it cost several runs to read as a startup race rather than as a protocol failure. A METADATA round
 * trip is the cheapest thing that cannot be answered by the proxy.
 *
 * Naming the topic makes the same call the run's other precondition: the broker does not create
 * topics, so a `BOOBLIK_TOPICS` that never arrived shows up here rather than as an empty fetch at
 * the end that reads like a lost batch.
 */
private suspend fun awaitBroker(port: Int, topic: TopicName, scope: CoroutineScope): BooblikConnection {
    var last: Throwable? = null
    repeat(ATTEMPTS) {
        val connection = runCatching { BooblikConnection(InetSocketAddress("127.0.0.1", port), scope) }
        val opened = connection.getOrNull()
        if (opened == null) {
            last = connection.exceptionOrNull()
        } else {
            val ready =
                runCatching {
                    val answer = opened.metadata(listOf(topic))
                    check(answer.error == ErrorCode.NONE) { "METADATA answered ${answer.error}" }
                    check(answer.topics.singleOrNull()?.partitions?.isNotEmpty() == true) {
                        "the broker is up but does not know ${topic.value}: ${answer.topics}"
                    }
                }
            if (ready.isSuccess) return opened
            last = ready.exceptionOrNull()
            runCatching { opened.close() }
        }
        delay(500)
    }
    error("the broker never answered for ${topic.value}: ${last?.message}")
}
