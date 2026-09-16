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
    /** The route that was driven — reported, because `work=3000ms` says nothing about `/items`. */
    val path: String,
    val connections: Int,
    val pid1: String,
) {
    /** Requests that were already outstanding when the signal was sent. */
    val spanningSignal: List<Exchange>
        get() = exchanges.filter { it.sentAtNanos <= signalAtNanos && it.finishedAtNanos >= signalAtNanos }

    val afterSignal: List<Exchange> get() = exchanges.filter { it.sentAtNanos > signalAtNanos }

    /**
     * The first moment readiness reported anything other than healthy.
     *
     * An **upper bound** on when the fall happened, not the moment itself: the poller samples on an
     * interval, so the flag flipped somewhere in `(readinessUpUntilNanos, readinessFellAtNanos]`.
     * Treating it as the moment is what made A4 fail every fast subject (#83).
     */
    val readinessFellAtNanos: Long?
        get() = readiness.firstOrNull { it.status != 200 }?.atNanos

    /**
     * The last moment readiness was demonstrably still healthy — the **lower** bound of the same
     * bracket, and the only instant an ordering violation can be proved against.
     *
     * A refusal earlier than this one happened while readiness was observed answering `200`, which no
     * sampling error can explain away. A refusal after it and before [readinessFellAtNanos] is inside
     * the interval the instrument cannot resolve.
     */
    val readinessUpUntilNanos: Long?
        get() =
            readinessFellAtNanos?.let { fell ->
                readiness.lastOrNull { it.atNanos < fell && it.status == 200 }?.atNanos
            }

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
    private val container: Subject,
    private val workMillis: Long,
    /** See `Options.path`. `{work}` is substituted here, once, rather than in every driver. */
    private val path: String = "/work?ms={work}",
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
                path = driven,
                connections = connections,
                pid1 = pid1,
            )
        } finally {
            container.remove()
        }
    }

    /**
     * The route with `{work}` resolved. Computed once: a driver that substituted per request would
     * be doing string work inside the loop the run is timing.
     */
    private val driven: String get() = path.replace("{work}", workMillis.toString())

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
            val exchange = client.get(driven)
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
            // A SUBJECT THAT DIED IS REPORTED AS HAVING DIED. Without this the wait runs its full
            // thirty seconds and says "never answered /health", which reads as a subject that will
            // not start and says nothing about why — and that message has already hidden two
            // different failures: a required configuration key nobody supplied, and an executable
            // path that did not resolve.
            if (!container.isAlive()) {
                client.close()
                error("the subject exited before it served — its own output above says why")
            }
            Thread.sleep(250)
        }
        client.close()
        error("the subject never answered /health within 30s")
    }
}
