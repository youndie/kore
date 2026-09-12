package io.github.youndie.kore.observability

import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.Environment
import io.github.youndie.kore.config.EnvironmentNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An environment a test hands over, listing exactly what it was given. */
private class Given(private val values: Map<String, String>) : Environment {
    override fun lookup(name: String): String? = values[name]

    override fun names(): EnvironmentNames = EnvironmentNames.Listed(values.keys)
}

private fun schema() = ConfigSchema("APP", keys = ObservabilityKeys.all, pairs = ObservabilityKeys.pairs)

class ObservabilityKeysTest {
    @Test
    fun `an endpoint without its key refuses the start and names both variables`() {
        val refusal =
            assertFailsWith<ConfigurationException> {
                schema().read(Given(mapOf("APP_SERVICE" to "orders", "APP_TRACY_ENDPOINT" to "https://tracy")))
            }

        val problem = refusal.problems.single { it.variable == "APP_TRACY_KEY" }
        assertTrue(
            problem.message.contains("APP_TRACY_ENDPOINT"),
            "the refusal did not name the variable that was set: ${problem.message}",
        )
    }

    /** Rule 2: both unset is a decision, not a mistake. */
    @Test
    fun `no agent configured is a valid configuration`() {
        val configuration = schema().read(Given(mapOf("APP_SERVICE" to "orders")))

        val settings = ObservabilitySettings.from(configuration, instanceFallback = "pod-1")

        assertTrue(!settings.anyAgentOn)
        assertEquals("orders", settings.service)
    }

    @Test
    fun `an agent with both halves set is on`() {
        val configuration =
            schema().read(
                Given(
                    mapOf(
                        "APP_SERVICE" to "orders",
                        "APP_RELEASE" to "1.4.0+abc123",
                        "APP_TRACY_ENDPOINT" to "https://tracy",
                        "APP_TRACY_KEY" to "secret",
                    ),
                ),
            )

        val settings = ObservabilitySettings.from(configuration, instanceFallback = "pod-1")

        assertEquals("https://tracy", settings.tracy?.endpoint)
        assertTrue(settings.anyAgentOn)
        assertEquals("1.4.0+abc123", settings.release)
    }

    /**
     * Rule 8. A declared key that nothing reads is the shape a deployment cannot tell from a working
     * one: the variable is accepted by the schema — so no refusal — and the queue stays wherever
     * katcher's default puts it. So this asserts the value arrives in the settings, not that the key
     * exists.
     */
    @Test
    fun `the katcher cache directory is read out of the environment`() {
        val configuration =
            schema().read(
                Given(
                    mapOf(
                        "APP_SERVICE" to "orders",
                        "APP_RELEASE" to "1.4.0+abc123",
                        "APP_KATCHER_ENDPOINT" to "https://katcher",
                        "APP_KATCHER_KEY" to "secret",
                        "APP_KATCHER_CACHE_DIR" to "/var/lib/orders/katcher",
                    ),
                ),
            )

        val settings = ObservabilitySettings.from(configuration, instanceFallback = "pod-1")

        assertEquals("/var/lib/orders/katcher", settings.katcherCacheDir)
    }

    /** Unset is the ordinary case: a service with no volume is better off with katcher's default. */
    @Test
    fun `no katcher cache directory leaves katcher its own default`() {
        val configuration =
            schema().read(
                Given(
                    mapOf(
                        "APP_SERVICE" to "orders",
                        "APP_RELEASE" to "1.4.0+abc123",
                        "APP_KATCHER_ENDPOINT" to "https://katcher",
                        "APP_KATCHER_KEY" to "secret",
                    ),
                ),
            )

        assertNull(ObservabilitySettings.from(configuration, instanceFallback = "pod-1").katcherCacheDir)
    }

    /** Rule 5: the pod name unless the deployment says otherwise. */
    @Test
    fun `the instance falls back to the name the host was given`() {
        val configuration = schema().read(Given(mapOf("APP_SERVICE" to "orders")))

        assertEquals("pod-7", ObservabilitySettings.from(configuration, instanceFallback = "pod-7").instance)
    }

    @Test
    fun `an explicit instance overrides the fallback`() {
        val configuration = schema().read(Given(mapOf("APP_SERVICE" to "orders", "APP_INSTANCE" to "declared")))

        assertEquals("declared", ObservabilitySettings.from(configuration, instanceFallback = "pod-7").instance)
    }

    /**
     * The key is a secret and `--print-config` masks it. Asserted here rather than trusted, because
     * the masking is a property of the declaration and a key declared without it prints in full.
     */
    @Test
    fun `the agent keys are declared as secrets`() {
        val keys = listOf(ObservabilityKeys.TRACY_KEY, ObservabilityKeys.METRIK_KEY, ObservabilityKeys.KATCHER_KEY)

        assertTrue(keys.all { it.secret }, "an agent key was declared without secret = true")
        assertTrue(
            listOf(ObservabilityKeys.TRACY_ENDPOINT, ObservabilityKeys.SERVICE).none { it.secret },
            "an endpoint or the service name was declared secret, which would mask the thing a reader needs",
        )
    }
}
