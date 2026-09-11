package io.github.youndie.kore.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val STORE_URL = ConfigKey.required("STORE_URL")
private val TIMEOUT = ConfigKey.millis("TIMEOUT_MS", 5_000.milliseconds)
private val API_KEY = ConfigKey.secret("API_KEY")
private val DEV_MODE = ConfigKey.boolean("DEV_MODE")
private val WORKERS = ConfigKey.int("WORKERS", 4)
private val TRACY_ENDPOINT = ConfigKey.optional("TRACY_ENDPOINT")
private val TRACY_KEY = ConfigKey.optional("TRACY_KEY", secret = true)

private val schema =
    ConfigSchema(
        prefix = "SAMPLE",
        keys = listOf(STORE_URL, TIMEOUT, API_KEY, DEV_MODE, WORKERS, TRACY_ENDPOINT, TRACY_KEY),
        pairs = listOf(ConfigPair("TRACY_ENDPOINT", "TRACY_KEY")),
    )

private fun env(vararg pairs: Pair<String, String>) = Environment.of(mapOf(*pairs))

private val minimal = arrayOf("SAMPLE_STORE_URL" to "postgres://x", "SAMPLE_API_KEY" to "s3cret")

class ConfigSchemaTest {
    @Test
    fun `a complete environment reads`() {
        val config = schema.read(env(*minimal, "SAMPLE_WORKERS" to "8", "SAMPLE_DEV_MODE" to "true"))

        assertEquals("postgres://x", config[STORE_URL])
        assertEquals(8, config[WORKERS])
        assertEquals(true, config[DEV_MODE])
        assertEquals(5_000.milliseconds, config[TIMEOUT])
        assertNull(config[TRACY_ENDPOINT])
    }

    @Test
    fun `a missing required variable names itself`() {
        val failure = assertFailsWith<ConfigurationException> { schema.read(env("SAMPLE_API_KEY" to "s")) }

        assertTrue(failure.message!!.contains("SAMPLE_STORE_URL"), "the message did not name the variable")
        assertTrue(failure.message!!.contains("required"))
    }

    /**
     * The reason errors are collected rather than thrown on the first.
     *
     * A process that fails on the first missing variable makes you fix them one at a time, one
     * restart each — and a deployment being configured for the first time has several.
     */
    @Test
    fun `everything wrong is reported at once`() {
        val failure = assertFailsWith<ConfigurationException> { schema.read(env("SAMPLE_WORKERS" to "lots")) }

        assertEquals(3, failure.problems.size, "the problems were not all collected: ${failure.problems}")
        assertEquals(
            setOf("SAMPLE_STORE_URL", "SAMPLE_API_KEY", "SAMPLE_WORKERS"),
            failure.problems.map { it.variable }.toSet(),
        )
    }

    @Test
    fun `a value that does not parse names the variable and what was wanted`() {
        val failure = assertFailsWith<ConfigurationException> { schema.read(env(*minimal, "SAMPLE_WORKERS" to "eight")) }

        val problem = failure.problems.single()
        assertEquals("SAMPLE_WORKERS", problem.variable)
        assertTrue(problem.message.contains("int"), "the message did not say what was expected")
        assertTrue(problem.message.contains("eight"), "the message did not quote what was there")
    }

    /**
     * Rule 6. A security switch that opens on a misspelling is the switch that ships open, so the
     * default is the closed position and there is no way to declare it otherwise.
     */
    @Test
    fun `a boolean is the exact string true and nothing else`() {
        listOf("True", "TRUE", "1", "yes", "on", " true").forEach { written ->
            val config = schema.read(env(*minimal, "SAMPLE_DEV_MODE" to written))
            assertEquals(false, config[DEV_MODE], "\"$written\" opened a switch")
        }
        assertEquals(true, schema.read(env(*minimal, "SAMPLE_DEV_MODE" to "true"))[DEV_MODE])
    }

    @Test
    fun `an unset switch is the closed position`() {
        assertEquals(false, schema.read(env(*minimal))[DEV_MODE])
    }

    @Test
    fun `half a pair is a refusal that names the missing half`() {
        val failure =
            assertFailsWith<ConfigurationException> {
                schema.read(env(*minimal, "SAMPLE_TRACY_ENDPOINT" to "http://tracy"))
            }

        val problem = failure.problems.single()
        assertEquals("SAMPLE_TRACY_KEY", problem.variable)
        assertTrue(problem.message.contains("SAMPLE_TRACY_ENDPOINT"), "the message did not name the half that was set")
    }

    @Test
    fun `neither half of a pair is a decision rather than a mistake`() {
        val config = schema.read(env(*minimal))

        assertNull(config[TRACY_ENDPOINT])
        assertNull(config[TRACY_KEY])
    }

    @Test
    fun `both halves of a pair read`() {
        val config = schema.read(env(*minimal, "SAMPLE_TRACY_ENDPOINT" to "http://tracy", "SAMPLE_TRACY_KEY" to "k"))

        assertEquals("http://tracy", config[TRACY_ENDPOINT])
    }

    /**
     * Rule 8. "The default was used" and "the environment said the same thing as the default" are
     * different facts, and the difference is what a renamed variable looks like.
     */
    @Test
    fun `an environment value that equals the default is still an environment value`() {
        val config = schema.read(env(*minimal, "SAMPLE_WORKERS" to "4"))

        assertEquals(Origin.ENV, config.values().single { it.variable == "SAMPLE_WORKERS" }.origin)
        assertEquals(Origin.DEFAULT, schema.read(env(*minimal)).values().single { it.variable == "SAMPLE_WORKERS" }.origin)
    }

    @Test
    fun `a secret is masked wherever it is rendered`() {
        val config = schema.read(env(*minimal))

        val secret = config.values().single { it.variable == "SAMPLE_API_KEY" }
        assertEquals("••••••", secret.rendered)
        assertTrue(config.values().none { it.rendered.contains("s3cret") }, "a secret appeared in the rendering")
        assertEquals("s3cret", config[API_KEY], "masking changed the value the program reads")
    }

    @Test
    fun `a blank value is an unset value`() {
        // A chart that renders an empty string for an absent setting is the ordinary case, and
        // treating "" as "set" turns that into a required variable that is present and useless.
        val failure = assertFailsWith<ConfigurationException> { schema.read(env(*minimal, "SAMPLE_STORE_URL" to "   ")) }
        assertEquals("SAMPLE_STORE_URL", failure.problems.single().variable)
    }

    @Test
    fun `a schema without a prefix is refused`() {
        assertFailsWith<IllegalArgumentException> { ConfigSchema("", listOf(STORE_URL)) }
    }

    @Test
    fun `a variable declared twice is refused`() {
        assertFailsWith<IllegalArgumentException> { ConfigSchema("SAMPLE", listOf(STORE_URL, STORE_URL)) }
    }

    @Test
    fun `a pair naming an undeclared variable is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigSchema("SAMPLE", listOf(STORE_URL), listOf(ConfigPair("STORE_URL", "NOPE")))
        }
    }
}
