package io.github.youndie.kore.ktor

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **On the JVM, readiness answers `503` on a new connection for the whole announce** — #90's
 * acceptance, asserted with a real `SIGTERM` against a real process.
 *
 * Every probe opens a connection of its own, the way a kubelet does. A probe riding one keep-alive
 * connection is what hid this: the engine keeps serving the sockets it has after its listener is
 * gone, so such a probe sees kore's `503` either way.
 */
class KtorShutdownHookJvmTest {
    @Test
    fun `under startForKore new connections see 503 through the announce`() {
        val statuses = probeThroughAnnounce("kore")

        assertTrue(statuses.isNotEmpty(), "no probe landed inside the announce")
        assertTrue(null !in statuses, "a new connection was refused inside the announce: $statuses")
        assertTrue(503 in statuses, "readiness never said 503: $statuses")
    }

    /**
     * The control, and the reason the test above means anything: the same subject with Ktor's hook
     * left on must be refused. If this goes green, the probe cannot see the defect.
     */
    @Test
    fun `with Ktor's hook left on the listener is gone at the signal`() {
        val statuses = probeThroughAnnounce("plain")

        assertTrue(null in statuses, "the control was never refused, so the probe cannot see #90: $statuses")
    }

    @Test
    fun `EngineDrain refuses to be built beside Ktor's hook`() {
        // INTO A FILE, AND WAIT BEFORE READING. Reading a pipe to its end blocks for as long as the
        // subject lives, and a subject whose guard is gone lives forever: with the check mutated out
        // this test did not go red, it hung for ten minutes. Killing it then closes the pipe, and the
        // test went red for `Stream closed` — a kill that names the harness rather than the guard.
        val log = File.createTempFile("kore-guarded", ".log").apply { deleteOnExit() }
        val subject = spawn("guarded", log)
        val exited = subject.waitFor(30, TimeUnit.SECONDS)
        if (!exited) subject.destroyForcibly().waitFor()
        val output = log.readText()

        assertTrue(exited, "the guarded subject served instead of refusing: $output")
        assertEquals(3, subject.exitValue(), output)
        assertTrue("startForKore" in output, "the refusal did not name the fix: $output")
    }

    /** Starts the subject, signals it, and returns what new connections got during the announce. */
    private fun probeThroughAnnounce(mode: String): List<Int?> {
        val subject = spawn(mode)
        try {
            val lines = BufferedReader(InputStreamReader(subject.inputStream))
            val port =
                generateSequence { lines.readLine() }
                    .firstOrNull { it.startsWith("PORT=") || it.startsWith("REFUSED") }
                    ?.takeIf { it.startsWith("PORT=") }
                    ?.removePrefix("PORT=")
                    ?.toInt()
                    ?: fail("the $mode subject never served")
            // Drain the rest so a chatty subject cannot block on a full pipe.
            Thread({ lines.forEachLine { } }, "subject-output").apply { isDaemon = true }.start()

            assertEquals(200, probe(port), "readiness was not 200 before the signal")

            subject.destroy() // SIGTERM on Linux and macOS
            val signalled = System.nanoTime()
            // Stop 300 ms short of the end: the last probe before the drain may land just after it.
            val until = signalled + (HOOK_SUBJECT_ANNOUNCE.inWholeMilliseconds - 300) * 1_000_000
            val statuses = mutableListOf<Int?>()
            while (System.nanoTime() < until) {
                statuses += probe(port)
                Thread.sleep(100)
            }
            assertTrue(subject.waitFor(30, TimeUnit.SECONDS), "the $mode subject did not exit after SIGTERM")
            return statuses
        } finally {
            subject.destroyForcibly()
        }
    }

    /** One probe on a new connection; `null` when the connection itself failed. */
    private fun probe(port: Int): Int? =
        runCatching {
            val connection = URI("http://127.0.0.1:$port${KoreRoutes.READY}").toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty("Connection", "close")
            connection.connectTimeout = 500
            connection.readTimeout = 1_000
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    /**
     * A fresh JVM, on this test's classpath, **without** the property the build sets for the in-process
     * suites — the subject has to meet Ktor's default, or the control would be green by configuration.
     */
    private fun spawn(mode: String, output: File? = null): Process {
        val classpath = System.getProperty("kore.test.classpath") ?: fail("kore.test.classpath is not set by the build")
        val java = File(System.getProperty("java.home"), "bin/java").path
        return ProcessBuilder(java, "-cp", classpath, HookSubject::class.java.name, mode)
            .redirectErrorStream(true)
            .apply { if (output != null) redirectOutput(output) }
            .start()
    }
}
