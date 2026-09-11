package io.github.youndie.kore.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentTest {
    @Test
    fun `a supplied map can be listed`() {
        val names = Environment.of(mapOf("A" to "1", "B" to "2")).names()

        assertTrue(names is EnvironmentNames.Listed)
        assertEquals(setOf("A", "B"), (names as EnvironmentNames.Listed).names)
    }

    /**
     * The distinction the whole type exists for.
     *
     * "Cannot list" and "listed nothing" must not be the same value. If they were, a deployment on a
     * target that cannot list would read "no unknown variables found" as evidence — and a check that
     * always passes is worse than one that is absent.
     */
    @Test
    fun `cannot list is a different answer from listed nothing`() {
        val empty = Environment.of(emptyMap()).names()
        val cannot = Environment.unlistable(emptyMap(), reason = "this target has no way").names()

        assertTrue(empty is EnvironmentNames.Listed, "an empty environment is still a listed one")
        assertTrue(cannot is EnvironmentNames.Unavailable, "an unlistable environment reported as listed")
        assertTrue((cannot as EnvironmentNames.Unavailable).reason.isNotBlank(), "it did not say why")
    }

    @Test
    fun `an unlistable environment can still be read by name`() {
        val environment = Environment.unlistable(mapOf("A" to "1"), reason = "no")

        assertEquals("1", environment.lookup("A"))
    }
}
