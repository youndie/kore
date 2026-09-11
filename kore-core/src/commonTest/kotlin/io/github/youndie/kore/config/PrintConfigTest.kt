package io.github.youndie.kore.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val URL = ConfigKey.required("STORE_URL")
private val WORKERS = ConfigKey.int("WORKERS", 4)
private val KEY = ConfigKey.secret("API_KEY")

private val schema = ConfigSchema("SAMPLE", listOf(URL, WORKERS, KEY))

class PrintConfigTest {
    private fun env(vararg pairs: Pair<String, String>) = Environment.of(mapOf(*pairs))
    private val complete = arrayOf("SAMPLE_STORE_URL" to "postgres://x", "SAMPLE_API_KEY" to "s3cret")

    @Test
    fun `every declared key appears with its origin`() {
        val printed = schema.printConfig(env(*complete))

        assertContains(printed.text, "SAMPLE_STORE_URL")
        assertContains(printed.text, "env")
        assertContains(printed.text, "SAMPLE_WORKERS")
        assertContains(printed.text, "default")
        assertEquals(0, printed.exitCode)
    }

    @Test
    fun `a secret never appears`() {
        val printed = schema.printConfig(env(*complete))

        assertFalse(printed.text.contains("s3cret"), "the secret was printed")
        assertContains(printed.text, "••••••")
    }

    /**
     * The case the flag exists for. A route needs a process that started; this is asked precisely
     * when one did not.
     */
    @Test
    fun `an unusable configuration still prints what it resolved`() {
        val printed = schema.printConfig(env("SAMPLE_WORKERS" to "8"))

        assertEquals(1, printed.exitCode, "an unusable configuration exited zero")
        assertContains(printed.text, "SAMPLE_WORKERS")
        assertContains(printed.text, "8")
        assertContains(printed.text, "will not start")
        assertContains(printed.text, "SAMPLE_STORE_URL")
        assertContains(printed.text, "SAMPLE_API_KEY")
    }

    @Test
    fun `the message is the one the start would give`() {
        val printed = schema.printConfig(env())
        val fromStart = runCatching { schema.read(env()) }.exceptionOrNull()!!

        schema.attempt(env()).problems.forEach { problem ->
            assertContains(printed.text, problem.variable)
            assertContains(fromStart.message!!, problem.variable)
        }
    }

    @Test
    fun `an unknown variable under the prefix is listed`() {
        val printed = schema.printConfig(env(*complete, "SAMPLE_TIMEOUT_MSEC" to "1000"))

        // The near-miss: declared SAMPLE_WORKERS, set SAMPLE_TIMEOUT_MSEC. B-24 makes it a refusal;
        // here it is at least visible.
        assertContains(printed.text, "SAMPLE_TIMEOUT_MSEC")
    }

    @Test
    fun `a variable outside the prefix is not the schema's business`() {
        val printed = schema.printConfig(env(*complete, "PATH" to "/usr/bin", "KUBERNETES_SERVICE_HOST" to "10.0.0.1"))

        assertContains(printed.text, "none — the environment was listed and checked")
        assertFalse(printed.text.contains("KUBERNETES_SERVICE_HOST"), "an unrelated variable was reported")
    }

    /**
     * The line that must be there in **both** cases.
     *
     * A deployment reading "no unknown variables found" has to be able to tell that from "nothing
     * was looked at", and the only way to be sure is for the check to state its own availability
     * every time.
     */
    @Test
    fun `a target that cannot list says so instead of reporting none`() {
        val unlistable = Environment.unlistable(mapOf(*complete), reason = "macOS native has no environ")

        val printed = schema.printConfig(unlistable)

        assertContains(printed.text, "NOT CHECKED")
        assertContains(printed.text, "macOS native has no environ")
        assertFalse(printed.text.contains("none —"), "an unlistable target reported that it found none")
    }

    @Test
    fun `extra sections are appended verbatim`() {
        val printed = schema.printConfig(env(*complete), extraSections = listOf("probes:\n  startupProbe: x\n"))

        assertContains(printed.text, "startupProbe: x")
    }
}
