package io.github.youndie.kore.oracle

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The three numbers of `docs/research/research-oracle.md` §4, taken as comparisons.
 *
 * A separate run from the oracle on purpose. The oracle answers *did the ordering hold*; this answers
 * *what did it cost*, and mixing them would mean every measurement pays for the oracle's assertions
 * and every assertion waits on a measurement.
 */
class Measurement(
    val image: String,
    val arm: String,
    /** From just before `docker run` to the first `200` on a route **both arms serve**. */
    val firstHealthMillis: Long,
    /** kore only: the first `200` on `/health/startup`, which is the probe that means something. */
    val firstStartupMillis: Long?,
    /** `VmRSS` of PID 1, read from the host at the moment the subject first reports itself ready. */
    val rssKbAtReady: Long?,
    /** `SIGTERM` to exit, under load. */
    val stopMillis: Long?,
    val exitCode: Int?,
    val inFlightAtSignal: Int,
    /** Whether the kore arm served `/version` at all — the staleness guard. `null` for the control. */
    val servedVersion: Boolean?,
    /** Requests outstanding when the signal was sent that then failed or answered 5xx. */
    val droppedAtSignal: Int,
    /** Requests outstanding when the signal was sent that completed normally. */
    val finishedAtSignal: Int,
)

/**
 * One cell: start, time the probes, sample RSS, put it under load, stop it.
 *
 * @param koreArm whether the subject serves kore's probes. The control does not have them at all —
 *   it serves one `/health` for all three, which is what the first consumer does — so the
 *   cross-arm number has to be taken on `/health`, the only route both answer. Comparing
 *   `/health/startup` against a `404` would be comparing a route to its absence.
 */
class MeasureRun(
    private val image: String,
    private val arm: String,
    private val koreArm: Boolean,
    private val subjectArgs: List<String>,
    private val workMillis: Long,
    private val connections: Int,
    private val readTimeoutMillis: Int,
    private val graceMillis: Long,
) {
    fun execute(): Measurement {
        val container = Container(image, subjectArgs)
        // Before `docker run`, so the number includes the runtime's own start. That overhead is the
        // same for both arms and cancels in the comparison; excluding it would mean timing from a
        // moment neither a kubelet nor a person can observe.
        val startedAt = System.nanoTime()
        container.start()
        try {
            val firstHealth = pollUntil200(container.port, "/health") ?: error("$image/$arm never answered /health")
            val firstHealthMillis = (firstHealth - startedAt) / 1_000_000

            val firstStartupMillis =
                if (!koreArm) {
                    null
                } else {
                    val at = pollUntil200(container.port, "/health/startup")
                        ?: error("$image/$arm never answered /health/startup")
                    (at - startedAt) / 1_000_000
                }

            // "Ready" means different things to the two arms and that is the finding, not a flaw:
            // the control has no readiness, so the moment it is willing to take traffic is the moment
            // it answers at all. Sampled at each arm's own answer to the same question.
            val readyRoute = if (koreArm) "/health/ready" else "/health"
            pollUntil200(container.port, readyRoute) ?: error("$image/$arm never became ready")
            val rss = container.rssKb()

            // THE STALENESS GUARD, and it exists because the alternative was found the hard way.
            // The image is built from artefacts produced outside Docker, so an image can be a
            // perfectly valid build of code that is hours old — and it fails nothing, it just
            // measures the wrong binary. `/version` arrived with B-27; an image without it predates
            // that, which is enough to refuse on. The check costs one request.
            val release =
                if (!koreArm) {
                    null
                } else {
                    val client = KeepAliveClient("127.0.0.1", container.port, readTimeoutMillis)
                    val answered = runCatching { client.get("/version") }.getOrNull()
                    client.close()
                    val served = answered?.status == 200
                    check(served) {
                        "$image is stale: its kore arm does not serve /version, which kore has had " +
                            "since B-27. Rebuild the binaries AND the image before measuring."
                    }
                    served
                }

            val stop = AtomicBoolean(false)
            val exchanges = ConcurrentLinkedQueue<Exchange>()
            val steady = CountDownLatch(connections)
            val outstanding = java.util.concurrent.atomic.AtomicInteger(0)
            val drivers = (1..connections).map { driver(container, it, exchanges, stop, outstanding, steady) }
            drivers.forEach { it.start() }
            steady.await()

            val inFlight = outstanding.get()
            val signalAt = System.nanoTime()
            container.sigterm()
            val exit = container.awaitExit(graceMillis + 10_000, TimeUnit.MILLISECONDS)
            val stopMillis = if (exit == null) null else (System.nanoTime() - signalAt) / 1_000_000

            stop.set(true)
            drivers.forEach { it.join(10_000) }

            // What became of the work that was in flight when the signal landed. Without this the
            // stop time is a number with no meaning: a process that drops everything stops sooner
            // than one that finishes it, and the faster figure is the worse behaviour.
            val spanning = exchanges.filter { it.sentAtNanos <= signalAt && it.finishedAtNanos >= signalAt }
            val dropped = spanning.count { it.failure != null || (it.status ?: 0) >= 500 }

            return Measurement(
                image, arm, firstHealthMillis, firstStartupMillis, rss, stopMillis, exit, inFlight, release,
                dropped, spanning.size - dropped,
            )
        } finally {
            container.remove()
        }
    }

    /**
     * Every 10 ms, not the oracle's 250: the interval is the precision of the answer.
     *
     * **One connection, reused.** The first version opened and closed a socket per poll, which at
     * 100 per second fills the ephemeral port range with sockets in `TIME_WAIT` — and then
     * `docker run -p 0:8080` cannot bind a host port and the run dies with *"address already in
     * use"*. The harness had broken the harness, which is the one failure that looks exactly like a
     * result. It reconnects only when the connection is refused, which is the normal state before
     * the server is listening.
     */
    private fun pollUntil200(port: Int, path: String): Long? {
        val deadline = System.nanoTime() + 60_000L * 1_000_000
        var client: KeepAliveClient? = null
        try {
            while (System.nanoTime() < deadline) {
                if (client == null) client = runCatching { KeepAliveClient("127.0.0.1", port, POLL_READ_TIMEOUT_MILLIS) }.getOrNull()
                val answered = client?.let { runCatching { it.get(path) }.getOrNull() }
                if (answered?.status == 200) return answered.finishedAtNanos
                if (answered == null || answered.failure != null) {
                    client?.close()
                    client = null
                }
                Thread.sleep(10)
            }
            return null
        } finally {
            client?.close()
        }
    }

    private companion object {
        /**
         * A poll that hangs must not become the measurement.
         *
         * The run's own read timeout is 30 s, which is right for a request that is *meant* to take
         * seconds. Applied to a probe poll it means one half-open connection costs 30 s of a number
         * whose unit is milliseconds — a 16.9 s "time to first /health" was produced exactly that
         * way. A probe either answers promptly or is not answering.
         */
        const val POLL_READ_TIMEOUT_MILLIS = 1_000
    }

    private fun driver(
        container: Container,
        index: Int,
        into: ConcurrentLinkedQueue<Exchange>,
        stop: AtomicBoolean,
        outstanding: java.util.concurrent.atomic.AtomicInteger,
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
            if (exchange.failure != null) break
        }
        client.close()
    }, "measure-driver-$index").apply { isDaemon = true }
}
