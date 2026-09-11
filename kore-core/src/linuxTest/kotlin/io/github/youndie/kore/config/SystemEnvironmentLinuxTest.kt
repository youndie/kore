package io.github.youndie.kore.config

import io.github.youndie.kore.korePlatform
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * The walk, against a **second, independent** enumeration of the same thing.
     *
     * `/proc/self/environ` is the kernel's own answer: the same `NAME=value` entries, NUL-separated,
     * reached through a completely different mechanism. Two implementations of one question, and
     * only one of them is the code under test.
     *
     * It exists because the obvious assertions did not earn their place. "PATH is among the names"
     * catches a walk that drops its first entry **only if PATH happens to be first**, and "every
     * listed name resolves" catches a split on the wrong `=` **only if the process happens to hold a
     * value containing one**. Both passed against a deliberately broken walk. An assertion whose
     * power depends on the ambient environment works until the day it matters.
     */
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `the walk agrees with the kernel's own answer`() {
        val fromKernel = readProcSelfEnviron()
        assertTrue(fromKernel.isNotEmpty(), "/proc/self/environ gave nothing; this test would prove nothing")

        val fromWalk = (systemEnvironment().names() as EnvironmentNames.Listed).names

        assertEquals(fromKernel, fromWalk, "the walk and /proc/self/environ disagree")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readProcSelfEnviron(): Set<String> {
        val file = fopen("/proc/self/environ", "rb") ?: return emptySet()
        val bytes = StringBuilder()
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(BUFFER)
                while (true) {
                    val read = fread(buffer, 1u, BUFFER.toULong(), file).toInt()
                    if (read <= 0) break
                    for (i in 0 until read) bytes.append(buffer[i].toInt().toChar())
                }
            }
        } finally {
            fclose(file)
        }
        return bytes.toString()
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .map { entry -> entry.substringBefore('=') }
            .toSet()
    }

    private companion object {
        const val BUFFER = 4096
    }

    @Test
    fun `the platform reports the capability the environment actually has`() {
        // One source of truth since B-22: this used to be a constant beside the implementation that
        // had to match it.
        assertTrue(korePlatform().canEnumerateEnvironment)
    }
}
