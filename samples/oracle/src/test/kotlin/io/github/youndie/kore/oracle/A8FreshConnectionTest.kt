package io.github.youndie.kore.oracle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A8 reads the new-connection poller, because the keep-alive one was blind to #90.**
 *
 * The signal is at 1000 ms and the announce lasts 5000 ms. The keep-alive samples are the same in
 * every case — healthy, then `503` — which is exactly what the JVM sample showed while its listener
 * was gone; only the new-connection samples differ.
 */
private const val MS = 1_000_000L
private const val SIGNAL = 1_000 * MS

private fun observations(fresh: List<ProbeSample>): Observations =
    Observations(
        exchanges = emptyList(),
        readiness = listOf(ProbeSample(900 * MS, 200), ProbeSample(1_100 * MS, 503)),
        signalAtNanos = SIGNAL,
        inFlightAtSignal = 8,
        exitCode = 143,
        exitAfterSignalMillis = 6_000,
        workMillis = 3_000,
        path = "/work?ms=3000",
        connections = 8,
        pid1 = "java -jar service-all.jar",
        freshReadiness = fresh,
    )

/** One sample every 100 ms from the signal to the end of the announce. */
private fun announce(status: (Long) -> Int?): List<ProbeSample> =
    (0L..49L).map { i -> ProbeSample(SIGNAL + (i * 100 + 5) * MS, status(i * 100)) }

class A8FreshConnectionTest {
    @Test
    fun `a listener closed at the signal fails although the keep-alive poller saw 503`() {
        val finding = announceHeldTheListener(observations(announce { null }), 5_000)
        assertEquals(Verdict.FAIL, finding.verdict, finding.detail)
    }

    @Test
    fun `one refusal in the middle of the announce fails`() {
        val finding = announceHeldTheListener(observations(announce { if (it == 2_500L) null else 503 }), 5_000)
        assertEquals(Verdict.FAIL, finding.verdict, finding.detail)
    }

    /** kore#94: `null` covers a refusal, a reset and a timeout, and only the failure says which. */
    @Test
    fun `a failing finding names what the client saw`() {
        val fresh =
            announce { 503 }.mapIndexed { i, sample ->
                if (i == 12) ProbeSample(sample.atNanos, null, "java.net.ConnectException: Connection refused") else sample
            }
        val finding = announceHeldTheListener(observations(fresh), 5_000)
        assertEquals(Verdict.FAIL, finding.verdict, finding.detail)
        assertTrue("ConnectException: Connection refused" in finding.detail, finding.detail)
        assertTrue("1205ms" in finding.detail, finding.detail)
    }

    @Test
    fun `503 on every new connection passes`() {
        val finding = announceHeldTheListener(observations(announce { 503 }), 5_000)
        assertEquals(Verdict.PASS, finding.verdict, finding.detail)
    }

    @Test
    fun `a refusal in the last two polls is the drain and passes`() {
        val finding = announceHeldTheListener(observations(announce { if (it >= 4_900L) null else 503 }), 5_000)
        assertEquals(Verdict.PASS, finding.verdict, finding.detail)
    }

    @Test
    fun `no sample inside the announce is inconclusive rather than a pass`() {
        val finding = announceHeldTheListener(observations(emptyList()), 5_000)
        assertEquals(Verdict.INCONCLUSIVE, finding.verdict, finding.detail)
    }

    @Test
    fun `without a pre-drain wait there is no announce to hold`() {
        val finding = announceHeldTheListener(observations(announce { null }), null)
        assertEquals(Verdict.NOT_APPLICABLE, finding.verdict, finding.detail)
    }
}
