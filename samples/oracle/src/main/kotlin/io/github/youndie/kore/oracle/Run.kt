package io.github.youndie.kore.oracle

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One poll of a probe, with when it happened. */
data class ProbeSample(val atNanos: Long, val status: Int?)

/** Everything the run observed. Assertions read this and nothing else — never the server's log. */
class Observations(
    val exchanges: List<Exchange>,
    val readiness: List<ProbeSample>,
    val signalAtNanos: Long,
    val inFlightAtSignal: Int,
    val exitCode: Int?,
    val exitAfterSignalMillis: Long?,
    val workMillis: Long,
    val connections: Int,
    val pid1: String,
) {
    /** Requests that were already outstanding when the signal was sent. */
    val spanningSignal: List<Exchange>
        get() = exchanges.filter { it.sentAtNanos <= signalAtNanos && it.finishedAtNanos >= signalAtNanos }

    val afterSignal: List<Exchange> get() = exchanges.filter { it.sentAtNanos > signalAtNanos }

    /** The first moment readiness reported anything other than healthy. */
    val readinessFellAtNanos: Long?
        get() = readiness.firstOrNull { it.status != 200 }?.atNanos

    /**
     * True when the subject has no readiness endpoint at all — the control does not.
     *
     * Written as "every answered poll was a 404", not "every poll was": once the server goes away
     * the poller records `null`s, and `all { it.status == 404 }` is then false for a subject that
     * plainly has no such endpoint. The first version said the latter and made A4 report **PASS**
     * against the control, which has nothing to pass — found by running it.
     */
    val readinessAbsent: Boolean
        get() = readiness.any { it.status == 404 } && readiness.none { it.status != null && it.status != 404 }
}

/**
 * Drives the load, sends the signal, and collects what happened.
 *
 * Closed loop: a fixed number of connections, each looping on the slow route. Open-loop load would
 * queue behind the subject and the numbers would describe the queue.
 */
class OracleRun(
    private val container: Container,
    private val workMillis: Long,
    private val connections: Int,
    private val readTimeoutMillis: Int,
    private val graceMillis: Long,
) {
    fun execute(): Observations {
        container.start()
        val pid1 = container.pid1()
        try {
            awaitServing()

            val exchanges = ConcurrentLinkedQueue<Exchange>()
            val readiness = ConcurrentLinkedQueue<ProbeSample>()
            val stop = AtomicBoolean(false)
            val outstanding = AtomicInteger(0)
            val steady = CountDownLatch(connections)
            val signalAt = AtomicLong(0)

            val drivers = (1..connections).map { driverThread(it, exchanges, stop, outstanding, steady) }
            val poller = readinessThread(readiness, stop)
            drivers.forEach { it.start() }
            poller.start()

            // Steady state is every connection having completed at least one request. Sending the
            // signal before that measures a warm-up.
            steady.await()

            val inFlight = outstanding.get()
            signalAt.set(System.nanoTime())
            container.sigterm()

            val exit = container.awaitExit(graceMillis + 10_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            val exitAfter = (System.nanoTime() - signalAt.get()) / 1_000_000

            stop.set(true)
            drivers.forEach { it.join(10_000) }
            poller.join(5_000)

            return Observations(
                exchanges = exchanges.toList().sortedBy { it.sentAtNanos },
                readiness = readiness.toList().sortedBy { it.atNanos },
                signalAtNanos = signalAt.get(),
                inFlightAtSignal = inFlight,
                exitCode = exit,
                exitAfterSignalMillis = if (exit == null) null else exitAfter,
                workMillis = workMillis,
                connections = connections,
                pid1 = pid1,
            )
        } finally {
            container.remove()
        }
    }

    private fun driverThread(
        index: Int,
        into: ConcurrentLinkedQueue<Exchange>,
        stop: AtomicBoolean,
        outstanding: AtomicInteger,
        steady: CountDownLatch,
    ) = Thread({
        val client = KeepAliveClient("127.0.0.1", container.port, readTimeoutMillis)
        var first = true
        while (!stop.get()) {
            outstanding.incrementAndGet()
            val exchange = client.get("/work?ms=$workMillis")
            outstanding.decrementAndGet()
            into += exchange
            if (first) {
                first = false
                steady.countDown()
            }
            // A connection that has died stops driving. Reconnecting after the signal would
            // manufacture requests the server never agreed to accept.
            if (exchange.failure != null) break
        }
        client.close()
    }, "oracle-driver-$index").apply { isDaemon = true }

    /**
     * Polls readiness on its own connection every 100 ms.
     *
     * Its own connection because a poller sharing a driver's socket would be measuring the driver's
     * queue, and 100 ms because the interval bounds how precisely A4 can place the fall.
     */
    private fun readinessThread(into: ConcurrentLinkedQueue<ProbeSample>, stop: AtomicBoolean) =
        Thread({
            val client = KeepAliveClient("127.0.0.1", container.port, readTimeoutMillis)
            while (!stop.get()) {
                val exchange = client.get("/health/ready")
                into += ProbeSample(exchange.finishedAtNanos, exchange.status)
                if (exchange.failure != null) break
                Thread.sleep(100)
            }
            client.close()
        }, "oracle-readiness").apply { isDaemon = true }

    private fun awaitServing() {
        val client = KeepAliveClient("127.0.0.1", container.port, readTimeoutMillis)
        repeat(120) {
            if (runCatching { client.get("/health").status == 200 }.getOrDefault(false)) {
                client.close()
                return
            }
            Thread.sleep(250)
        }
        client.close()
        error("the container never answered /health")
    }
}
