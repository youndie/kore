package io.github.youndie.kore.oracle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A5 reads the same bracket as A4** (kore#94, B-61).
 *
 * The signal is at 1000 ms, readiness was last seen `200` at 990 ms and first seen `503` at 1090 ms,
 * so the flag flipped in `(1000, 1090]` — the signal is the tighter lower bound. The wait is 5000 ms.
 */
private const val MS = 1_000_000L

private fun a5(refusalAtMillis: Long): Finding {
    val observations =
        Observations(
            exchanges =
                listOf(
                    Exchange(
                        sentAtNanos = (refusalAtMillis - 1) * MS,
                        finishedAtNanos = refusalAtMillis * MS,
                        status = 503,
                        headers = mapOf("connection" to "close"),
                        bodyComplete = true,
                        connectionReused = true,
                        failure = null,
                    ),
                ),
            readiness = listOf(ProbeSample(890 * MS, 200), ProbeSample(990 * MS, 200), ProbeSample(1_090 * MS, 503)),
            signalAtNanos = 1_000 * MS,
            inFlightAtSignal = 32,
            exitCode = 0,
            exitAfterSignalMillis = 20_000,
            workMillis = 1,
            path = "/items",
            connections = 32,
            pid1 = "keel",
        )
    return evaluate(observations, preDrainWaitMillis = 5_000, graceMillis = 30_000).single { it.id.startsWith("A5") }
}

class A5BracketTest {
    /** A refusal at the drain of a correct run: the fall plus the wait, and the sample 90 ms late. */
    @Test
    fun `a refusal at the drain boundary passes although the sample was late`() {
        val finding = a5(6_010)
        assertEquals(Verdict.PASS, finding.verdict, finding.detail)
    }

    @Test
    fun `a refusal a full wait after the observed fall passes`() {
        assertEquals(Verdict.PASS, a5(6_100).verdict)
    }

    /** The mutation §3.3 names: the wait gone. And the defect of B-61: a refusal before the sample. */
    @Test
    fun `a refusal before the observed fall fails`() {
        val finding = a5(1_020)
        assertEquals(Verdict.FAIL, finding.verdict, finding.detail)
    }

    /** Short by more than any sampling error: 200 ms under the wait, measured from the signal itself. */
    @Test
    fun `a wait cut short beyond the poll interval fails`() {
        val finding = a5(5_800)
        assertEquals(Verdict.FAIL, finding.verdict, finding.detail)
    }

    /**
     * The signal is the tighter bound here: 5 ms under the wait counting from the signal, but 5 ms
     * over it counting from the last 200. Readiness cannot fall before the process was signalled.
     */
    @Test
    fun `the fall is bounded by the signal and not only by the last healthy sample`() {
        val finding = a5(5_995)
        assertEquals(Verdict.FAIL, finding.verdict, finding.detail)
    }
}
