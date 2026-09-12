package io.github.youndie.kore.observability

import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.Environment
import io.github.youndie.metrik.agent.MetrikConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * #68: metrik sends a window when the window **closes**, and its default is a minute — so a stand
 * whose whole end-to-end run is shorter than that reports nothing, and a test waiting on it passes
 * only when a window boundary happens to fall inside the wait.
 *
 * The first consumer saw exactly that: four passes against a stand that had been up a while, then a
 * failure on a freshly rebuilt one. A knob that turns a flaky test into a deterministic one is worth
 * more than the seconds it saves.
 */
private val schema =
    ConfigSchema(prefix = "APP", keys = ObservabilityKeys.all, pairs = ObservabilityKeys.pairs)

private val on =
    arrayOf(
        "APP_SERVICE" to "app",
        "APP_METRIK_ENDPOINT" to "metrik:9999",
        "APP_METRIK_KEY" to "k",
    )

private fun settingsFrom(vararg pairs: Pair<String, String>) =
    ObservabilitySettings.from(schema.read(Environment.of(mapOf(*pairs))), instanceFallback = "pod-1")

class MetrikWindowTest {
    @Test
    fun `a window set in the environment reaches metrik`() {
        val settings = settingsFrom(*on, "APP_METRIK_WINDOW_MS" to "1000")

        assertEquals(1_000.milliseconds, settings.metrikWindow)
        val config = MetrikConfig().apply { applyKore(settings, settings.metrik!!, release = null) }
        assertEquals(1_000L, config.windowMs)
    }

    /**
     * Unset must reach metrik as *not set* — which is a claim about kore **not writing the field**,
     * not about the value that ends up in it.
     *
     * The first version of this test compared against a fresh `MetrikConfig().windowMs`, and a
     * mutation replacing the pass-through with `?: 60_000L` **survived it**: kore pinning a number
     * that happens to equal metrik's default today is indistinguishable from kore leaving it alone,
     * and that pin is precisely the defect — it would outlive the day metrik changed its mind.
     *
     * A sentinel is what makes the claim visible. It also kills that mutant.
     */
    @Test
    fun `an unset window is not written at all`() {
        val settings = settingsFrom(*on)
        assertNull(settings.metrikWindow)

        val sentinel = 123_456L
        val config = MetrikConfig().apply { windowMs = sentinel }
        config.applyKore(settings, settings.metrik!!, release = null)

        assertEquals(sentinel, config.windowMs, "kore wrote a window when the deployment set none")
    }

    /** The rest of the wiring still happens — a knob that quietly replaced the config would pass above. */
    @Test
    fun `the other fields are still wired`() {
        val settings = settingsFrom(*on, "APP_METRIK_WINDOW_MS" to "1000")

        val config = MetrikConfig().apply { applyKore(settings, settings.metrik!!, release = "r1") }

        assertEquals("app", config.service)
        assertEquals("metrik:9999", config.endpoint)
        assertEquals("k", config.apiKey)
        assertEquals("pod-1", config.instanceId)
        assertEquals("r1", config.release)
    }

    @Test
    fun `a window that is not a number refuses at startup`() {
        val attempt = schema.attempt(Environment.of(mapOf(*on, "APP_METRIK_WINDOW_MS" to "a minute")))

        assertTrue(attempt.problems.any { it.variable == "APP_METRIK_WINDOW_MS" }, "${attempt.problems}")
    }
}
