package io.github.youndie.kore.oracle

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * **Does a pool really hand out a connection whose far end is gone?** — [B-47], and research §1.9
 * consequence 1, which was derived rather than observed.
 *
 * Everything kore says about dependency checks rests on that one sentence: rule 6 of
 * `feature-health-probes`, the shape of `storeCheck`, and the double in `PooledStoreCheckTest` that
 * reproduces it. A double is a model; this is what says the model is faithful.
 *
 * Invoked by name and never from `make build` or the oracle run: it needs a container, it answers the
 * same question every time, and a check that makes the ordinary gate slower is one people learn to
 * skip.
 *
 *     ./gradlew :samples:oracle:driverBehaviour
 *
 * **Both halves are asserted.** "The statement fails" alone would also pass against a pool that fails
 * `acquire` too — which is the behaviour kore's rule says does *not* happen. A check that cannot tell
 * those apart has not checked the claim.
 */
fun main() {
    // TWO WAYS FOR A SERVER TO GO AWAY, and they are not the same question.
    //
    // A clean `stop` closes the connections and the client is told — the case a pool notices most
    // easily. A `pause` freezes the process with its sockets open, so nothing is ever said and
    // nothing ever answers. §1.9 consequence 1 is about the second: "a pool can hand out a
    // connection from its idle set without the server on the far end being alive". The first version
    // of this check measured only the clean stop and would have refuted a claim it had not tested.
    val results =
        listOf("clean stop" to false, "paused — the far end gone silently" to true).map { (label, pause) ->
            val container = PostgresContainer()
            try {
                container.start()
                label to runBlocking { observe(container, pause) }
            } finally {
                if (pause) container.unpauseServer()
                container.remove()
            }
        }

    println()
    results.forEach { (label, outcome) ->
        println("=== $label ===")
        println(outcome.report())
        println()
    }

    // The claim is about the silent case. A clean stop refuting it says only that a client notices
    // being told, which nobody doubted.
    val silent = results.last().second
    exitProcess(if (silent.supportsTheClaim) 0 else 1)
}

private class Observation(
    val acquireSucceeded: Boolean,
    val acquireDetail: String,
    val statementFailed: Boolean,
    val statementDetail: String,
) {
    /** §1.9 consequence 1: the handle comes back, the statement does not. */
    val supportsTheClaim: Boolean get() = acquireSucceeded && statementFailed

    fun report(): String =
        buildString {
            appendLine("research §1.9 consequence 1:")
            appendLine("  acquire()  ${if (acquireSucceeded) "SUCCEEDED" else "FAILED"} — $acquireDetail")
            appendLine("  statement  ${if (statementFailed) "FAILED" else "SUCCEEDED"} — $statementDetail")
            appendLine()
            appendLine(
                if (supportsTheClaim) {
                    "SUPPORTED: a pool hands out a connection whose far end is gone, and only a " +
                        "statement notices. `storeCheck`'s shape and rule 6 stand."
                } else {
                    "REFUTED: amend research §1.9 consequence 1 at the point of divergence, and with " +
                        "it rule 6 of feature-health-probes and the double in PooledStoreCheckTest."
                },
            )
        }
}

private suspend fun observe(container: PostgresContainer, pause: Boolean): Observation {
    val port = container.port
    val pool =
        PostgreSQL(
            url = "postgresql://127.0.0.1:$port/kore",
            username = "kore",
            password = "kore",
            options = ConnectionPool.Options.builder().maxConnections(4).build(),
        )

    // A statement first, so the pool holds a connection that was genuinely alive. Without this the
    // run would be asking about a pool that had never connected, which is a different question.
    //
    // A real loop with a real exit: `repeat { … return@repeat }` leaves the *iteration*, not the
    // loop, so the first version kept retrying after it had already succeeded and then reported the
    // last attempt. Postgres takes a few seconds to accept connections after the container starts.
    var live = false
    var lastFailure: Throwable? = null
    for (attempt in 1..60) {
        val warmed = runCatching { pool.execute("select 1").getOrThrow() }
        if (warmed.isSuccess) {
            live = true
            println("pool is live against the server after $attempt attempt(s)")
            break
        }
        lastFailure = warmed.exceptionOrNull()
        delay(500)
    }
    check(live) { "the pool never reached the server: ${lastFailure?.summary()}" }

    if (pause) container.pauseServer() else container.stopServer()
    println(if (pause) "server paused — sockets open, nothing answering" else "server stopped cleanly")

    // BOUNDED, because a paused server does not refuse — it says nothing at all, and an unbounded
    // wait here is the check hanging rather than answering. A timeout counts as a failure and says
    // so, which is the honest reading: a probe that never returns has failed as far as a kubelet is
    // concerned, and that is the whole reason `HealthCheck` carries a timeout of its own.
    val acquired = bounded("acquire") { pool.acquire().getOrThrow() }
    val statement =
        acquired.fold(
            onSuccess = { connection -> bounded("statement") { connection.execute("select 1").getOrThrow() } },
            onFailure = { Result.failure(it) },
        )

    return Observation(
        acquireSucceeded = acquired.isSuccess,
        acquireDetail = acquired.fold({ "a connection object came back" }, { it.summary() }),
        statementFailed = statement.isFailure,
        statementDetail = statement.fold({ "it answered" }, { it.summary() }),
    )
}

/** A timeout is a failure with a name, not a hang. */
private class TookTooLong(what: String) : RuntimeException("$what did not answer within ${BOUND}")

private val BOUND = 10.seconds

private suspend fun <T> bounded(what: String, block: suspend () -> T): Result<T> =
    runCatching {
        withTimeoutOrNull(BOUND) { block() } ?: throw TookTooLong(what)
    }

private fun Throwable.summary(): String = "${this::class.simpleName}: ${message?.take(120)}"
