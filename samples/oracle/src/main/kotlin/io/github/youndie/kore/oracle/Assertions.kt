package io.github.youndie.kore.oracle

/**
 * The outcome of one assertion.
 *
 * `NOT_APPLICABLE` is a first-class result and not a synonym for `PASS`. The control sample has no
 * readiness endpoint at all, so A4 and A5 cannot be evaluated against it — and reporting them as
 * passed would be a suite claiming coverage it does not have, which is the failure mode this whole
 * repository is written against.
 */
enum class Verdict { PASS, FAIL, NOT_APPLICABLE, INCONCLUSIVE }

data class Finding(val id: String, val verdict: Verdict, val detail: String)

/**
 * The assertions of `docs/research/research-oracle.md` §2.3, plus the vacuity guards of §2.5.
 *
 * Every one reads [Observations] — the client's own record and the process's exit — and nothing
 * else. The server's log is written by the code under test; an oracle that reads it can be satisfied
 * by a comment.
 */
fun evaluate(observations: Observations, preDrainWaitMillis: Long?, graceMillis: Long): List<Finding> {
    val findings = mutableListOf<Finding>()

    // --- the vacuity guards first, because a run that visited nothing must not report a pass ------

    val inFlightFloor = maxOf(1, observations.connections / 2)
    findings +=
        if (observations.inFlightAtSignal >= inFlightFloor) {
            Finding("G1 in flight at the signal", Verdict.PASS, "${observations.inFlightAtSignal} of ${observations.connections}")
        } else {
            Finding(
                "G1 in flight at the signal",
                Verdict.INCONCLUSIVE,
                "only ${observations.inFlightAtSignal} of ${observations.connections} requests were outstanding; " +
                    "nothing was being drained, so the run proves nothing. Raise --work or --connections.",
            )
        }

    findings +=
        if (observations.exchanges.isNotEmpty()) {
            Finding("G2 the load ran", Verdict.PASS, "${observations.exchanges.size} exchanges, work=${observations.workMillis}ms")
        } else {
            Finding("G2 the load ran", Verdict.INCONCLUSIVE, "no request was ever made")
        }

    // A process that never saw SIGTERM looks exactly like one that shut down instantly. Exiting at
    // all after the signal is the evidence that it arrived — see A6.
    findings +=
        if (observations.exitCode != null) {
            Finding("G3 the signal was received", Verdict.PASS, "the process exited after it, ${observations.exitAfterSignalMillis}ms")
        } else {
            Finding(
                "G3 the signal was received",
                Verdict.FAIL,
                "the container did not exit within the grace period; either the signal never reached PID 1 " +
                    "(${observations.pid1}) or the process ignored it",
            )
        }

    // --- A1-A7 -----------------------------------------------------------------------------------

    val spanning = observations.spanningSignal
    val broken = spanning.filter { it.status == null || !it.bodyComplete }
    findings +=
        when {
            spanning.isEmpty() -> Finding("A1 in-flight requests finished", Verdict.INCONCLUSIVE, "no request spanned the signal")
            broken.isEmpty() -> Finding("A1 in-flight requests finished", Verdict.PASS, "${spanning.size} spanned the signal, all completed")
            else ->
                Finding(
                    "A1 in-flight requests finished",
                    Verdict.FAIL,
                    "${broken.size} of ${spanning.size} were cut off: " +
                        broken.take(3).joinToString("; ") { it.failure ?: "status=${it.status} bodyComplete=${it.bodyComplete}" },
                )
        }

    val fiveHundreds = observations.exchanges.filter { it.status != null && it.status in 500..599 && it.status != 503 }
    findings +=
        if (fiveHundreds.isEmpty()) {
            Finding("A2 no 500", Verdict.PASS, "every answered request was a normal status or 503")
        } else {
            Finding(
                "A2 no 500",
                Verdict.FAIL,
                "${fiveHundreds.size} responses in 5xx other than 503 — something ran against a closed resource",
            )
        }

    val refusals = observations.exchanges.filter { it.status == 503 }
    findings +=
        when {
            refusals.isEmpty() -> Finding("A3 503 carries Connection: close", Verdict.NOT_APPLICABLE, "nothing was refused with 503")
            refusals.all { it.connectionClose } -> Finding("A3 503 carries Connection: close", Verdict.PASS, "${refusals.size} refusals, all carrying it")
            else ->
                Finding(
                    "A3 503 carries Connection: close",
                    Verdict.FAIL,
                    "${refusals.count { !it.connectionClose }} of ${refusals.size} refusals did not carry it",
                )
        }

    val fell = observations.readinessFellAtNanos
    findings +=
        when {
            observations.readinessAbsent ->
                Finding(
                    "A4 readiness fell before the first refusal",
                    Verdict.NOT_APPLICABLE,
                    "the subject has no /health/ready — it cannot announce anything, which is what makes it the control",
                )
            fell == null -> Finding("A4 readiness fell before the first refusal", Verdict.FAIL, "readiness never stopped answering 200")
            else -> {
                val firstRefusal = refusals.minOfOrNull { it.finishedAtNanos }
                when {
                    // Nothing was refused, so there is no "before" to be on the right side of. Not a
                    // pass: an assertion with no subject has not been evaluated.
                    firstRefusal == null ->
                        Finding("A4 readiness fell before the first refusal", Verdict.NOT_APPLICABLE, "nothing was refused with 503")
                    fell < firstRefusal ->
                        Finding("A4 readiness fell before the first refusal", Verdict.PASS, "by ${(firstRefusal - fell) / 1_000_000}ms")
                    else ->
                        Finding("A4 readiness fell before the first refusal", Verdict.FAIL, "a request was refused before readiness fell")
                }
            }
        }

    findings +=
        when {
            observations.readinessAbsent -> Finding("A5 the pre-drain wait was honoured", Verdict.NOT_APPLICABLE, "no readiness endpoint")
            preDrainWaitMillis == null -> Finding("A5 the pre-drain wait was honoured", Verdict.NOT_APPLICABLE, "--pre-drain not given")
            fell == null -> Finding("A5 the pre-drain wait was honoured", Verdict.FAIL, "readiness never fell")
            else -> {
                val firstRefusal = refusals.minOfOrNull { it.finishedAtNanos }
                if (firstRefusal == null) {
                    Finding("A5 the pre-drain wait was honoured", Verdict.NOT_APPLICABLE, "nothing was refused")
                } else {
                    val gapMillis = (firstRefusal - fell) / 1_000_000
                    if (gapMillis >= preDrainWaitMillis) {
                        Finding("A5 the pre-drain wait was honoured", Verdict.PASS, "${gapMillis}ms >= ${preDrainWaitMillis}ms")
                    } else {
                        Finding("A5 the pre-drain wait was honoured", Verdict.FAIL, "${gapMillis}ms < ${preDrainWaitMillis}ms")
                    }
                }
            }
        }

    // A6 asserts HOW the process ended, not a number: a clean SIGTERM shutdown exits 0 on
    // Kotlin/Native and 143 on the JVM, and both are correct (measured in B-05). 137 is SIGKILL,
    // which is the failure this is looking for.
    findings +=
        when (val code = observations.exitCode) {
            null -> Finding("A6 exited itself inside the grace period", Verdict.FAIL, "did not exit within ${graceMillis}ms")
            137 -> Finding("A6 exited itself inside the grace period", Verdict.FAIL, "exit 137: it was SIGKILLed")
            else ->
                Finding(
                    "A6 exited itself inside the grace period",
                    if ((observations.exitAfterSignalMillis ?: Long.MAX_VALUE) <= graceMillis) Verdict.PASS else Verdict.FAIL,
                    "exit $code after ${observations.exitAfterSignalMillis}ms of ${graceMillis}ms",
                )
        }

    return findings
}
