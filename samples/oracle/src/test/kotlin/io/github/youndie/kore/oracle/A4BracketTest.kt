package io.github.youndie.kore.oracle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A4 compares an instant against an interval, and #83 is what that costs.**
 *
 * `readinessFellAtNanos` is the first sample that was not `200`, so the flag flipped somewhere in
 * `(readinessUpUntilNanos, readinessFellAtNanos]` — up to one poll interval wide. A refusal is a real
 * exchange with a real timestamp. Comparing the two directly made the assertion fail deterministically
 * for any subject whose route answers in about a millisecond, because such a driver is refused
 * microseconds after the signal and the poller has not sampled yet.
 *
 * Three cases, because the assertion now has three answers and each has to be reachable: a refusal
 * before the last observed `200` is a violation no sampling error explains, a refusal after the first
 * non-200 is clean, and one in between is a question this instrument cannot answer.
 */
private const val MS = 1_000_000L

private fun observations(
    readiness: List<ProbeSample>,
    refusalAtNanos: Long?,
): Observations {
    val exchanges =
        refusalAtNanos?.let {
            listOf(
                Exchange(
                    sentAtNanos = it - MS,
                    finishedAtNanos = it,
                    status = 503,
                    headers = mapOf("connection" to "close"),
                    bodyComplete = true,
                    connectionReused = true,
                    failure = null,
                ),
            )
        } ?: emptyList()
    return Observations(
        exchanges = exchanges,
        readiness = readiness,
        signalAtNanos = 1_000 * MS,
        inFlightAtSignal = 8,
        exitCode = 0,
        exitAfterSignalMillis = 100,
        workMillis = 1,
        path = "/items",
        connections = 8,
        pid1 = "[\"/app/service\"]",
    )
}

/** Polls at 100 ms: healthy up to 1000 ms, first non-200 at 1100 ms. The flag flipped in between. */
private val polls =
    listOf(
        ProbeSample(900 * MS, 200),
        ProbeSample(1_000 * MS, 200),
        ProbeSample(1_100 * MS, 503),
    )

private fun a4(refusalAtNanos: Long?) =
    evaluate(observations(polls, refusalAtNanos), preDrainWaitMillis = null, graceMillis = 30_000)
        .single { it.id.startsWith("A4") }

class A4BracketTest {
    /** Refused while readiness was *observed* answering 200. No sampling error reaches back that far. */
    @Test
    fun `a refusal before the last healthy sample is a violation`() {
        assertEquals(Verdict.FAIL, a4(950 * MS).verdict)
    }

    /** Refused after the fall had been observed. The ordering holds with room to spare. */
    @Test
    fun `a refusal after the fall was observed passes`() {
        assertEquals(Verdict.PASS, a4(1_200 * MS).verdict)
    }

    /**
     * The #83 case. Inside the interval the poller cannot resolve — which is what every fast route
     * produces, and which used to be reported as a failure of the subject rather than of the
     * instrument.
     */
    @Test
    fun `a refusal inside the poll interval is not answerable`() {
        val finding = a4(1_050 * MS)

        assertEquals(Verdict.NOT_APPLICABLE, finding.verdict)
        assertEquals(true, finding.detail.contains("100ms"), finding.detail)
    }
}
