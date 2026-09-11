package io.github.youndie.kore.config

import io.github.youndie.kore.korePlatform
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Against the **real** environment of the test process, through `__environ`.
 *
 * A fake would test the shape and not the walk, and the walk is the part that is platform-specific
 * and easy to get wrong — an off-by-one on the null terminator, or splitting a `NAME=value` on the
 * wrong `=`.
 */
class SystemEnvironmentLinuxTest {
    @Test
    fun `the environment can be listed on linux`() {
        val names = systemEnvironment().names()

        assertTrue(names is EnvironmentNames.Listed, "linux reported that it cannot list the environment")
        assertTrue((names as EnvironmentNames.Listed).names.isNotEmpty(), "the walk produced nothing")
    }

    @Test
    fun `PATH is among the names`() {
        val names = (systemEnvironment().names() as EnvironmentNames.Listed).names

        // Always set for a process started by a shell or a build, and a name whose VALUE contains no
        // `=` — so finding it proves the walk, and the next test proves the splitting.
        assertTrue("PATH" in names, "PATH was not listed: ${names.take(5)}")
    }

    /**
     * Every listed name resolves. This is what catches a split on the wrong `=`: a value containing
     * one — a connection string, a JVM options line — would produce a "name" that `getenv` cannot
     * find.
     */
    @Test
    fun `every listed name can be looked up`() {
        val environment = systemEnvironment()
        val names = (environment.names() as EnvironmentNames.Listed).names

        val unresolvable = names.filter { environment.lookup(it) == null }

        assertTrue(unresolvable.isEmpty(), "listed names that do not resolve: $unresolvable")
    }

    @Test
    fun `the platform reports the capability the environment actually has`() {
        // One source of truth since B-22: this used to be a constant beside the implementation that
        // had to match it.
        assertTrue(korePlatform().canEnumerateEnvironment)
    }
}
