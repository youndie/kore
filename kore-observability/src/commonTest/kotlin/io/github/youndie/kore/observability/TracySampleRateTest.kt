package io.github.youndie.kore.observability

import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.ConfigValueException
import io.github.youndie.kore.config.Environment
import io.github.youndie.tracy.agent.AgentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #57: a consumer that needs `sampleRate = 1.0` could not say so, and nothing said it could not.
 *
 * The rate does not thin spans — it decides whether the request's whole pending trace is kept — so at
 * the default of one in a hundred what a reference build demonstrates is entity references with no
 * bodies behind them. The first consumer found it by looking for a purchase it had just made.
 */
private val schema =
    ConfigSchema(
        prefix = "APP",
        keys = ObservabilityKeys.all,
        pairs = ObservabilityKeys.pairs,
    )

private val on =
    arrayOf(
        "APP_SERVICE" to "app",
        "APP_TRACY_ENDPOINT" to "https://tracy.example",
        "APP_TRACY_KEY" to "k",
    )

private fun settingsFrom(vararg pairs: Pair<String, String>) =
    ObservabilitySettings.from(schema.read(Environment.of(mapOf(*pairs))), instanceFallback = "pod-1")

class TracySampleRateTest {
    @Test
    fun `a rate set in the environment reaches tracy`() {
        val settings = settingsFrom(*on, "APP_TRACY_SAMPLE_RATE" to "1.0")

        assertEquals(1.0, settings.tracySampleRate)
        assertEquals(1.0, tracyAgentConfig(settings, settings.tracy!!, release = null).sampleRate)
    }

    /**
     * Unset must reach tracy as *not set*, not as kore's copy of tracy's number — so the expectation
     * is read out of `AgentConfig` rather than written as a literal. A test asserting `0.01` would go
     * green on the day tracy changed its default and kore started pinning the old one.
     *
     * **What this cannot see, stated because the neighbouring test can.** A pin equal to tracy's
     * default *today* — `?: 0.01` — passes here, because the value that lands is the same either way.
     * `MetrikWindowTest` catches its equivalent with a sentinel, and only because `MetrikConfig` is
     * mutable: the claim there is "kore does not write the field", which is observable, while
     * `AgentConfig` is built by constructor and this one can only compare the result. Found by a
     * mutation that survived the metrik version of this test before it was strengthened.
     */
    @Test
    fun `an unset rate leaves tracy's own default rather than a number of kore's`() {
        val settings = settingsFrom(*on)

        assertNull(settings.tracySampleRate)
        val tracysOwn = AgentConfig(service = "app", apiKey = "k", endpoint = "https://tracy.example", instanceId = "pod-1")
        assertEquals(tracysOwn.sampleRate, tracyAgentConfig(settings, settings.tracy!!, release = null).sampleRate)
    }

    /**
     * A rate that does not parse is a refusal, not a silent fallback. An agent disabled by a typo is
     * indistinguishable from a healthy one, which is the whole reason the schema refuses rather than
     * defaults.
     */
    @Test
    fun `a rate that is not a number refuses at startup`() {
        val attempt = schema.attempt(Environment.of(mapOf(*on, "APP_TRACY_SAMPLE_RATE" to "all")))

        assertTrue(attempt.problems.any { it.variable == "APP_TRACY_SAMPLE_RATE" }, "${attempt.problems}")
    }

    /** The rate is not a secret and not a pair — it renders as itself, which is what an operator reads. */
    @Test
    fun `the rate is printed rather than masked`() {
        val values = schema.read(Environment.of(mapOf(*on, "APP_TRACY_SAMPLE_RATE" to "0.5"))).values()

        assertEquals("0.5", values.single { it.variable == "APP_TRACY_SAMPLE_RATE" }.rendered)
    }
}
