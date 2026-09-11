package io.github.youndie.kore.oracle

import java.util.concurrent.TimeUnit

/**
 * The subject, driven from outside through `docker`.
 *
 * Outside on purpose: the run must observe a process it does not share a runtime with, and the
 * signal must land on PID 1 the way a kubelet sends it. Anything the harness could learn by being
 * inside the process is something it would be trusting the subject to tell it.
 */
class Container(private val image: String) {
    private var id: String? = null

    /** The host port the container's 8080 was published on. */
    var port: Int = 0
        private set

    fun start() {
        id = docker("run", "-d", "-p", "0:8080", image).trim()
        val mapping = docker("port", requireId(), "8080").lineSequence().first().trim()
        port = mapping.substringAfterLast(':').toInt()
    }

    /** `SIGTERM` to PID 1, which is what a kubelet does. */
    fun sigterm() {
        docker("kill", "-s", "TERM", requireId())
    }

    /** Blocks until the container exits; returns its exit code. */
    fun awaitExit(timeout: Long, unit: TimeUnit): Int? {
        val process = ProcessBuilder("docker", "wait", requireId()).redirectErrorStream(true).start()
        if (!process.waitFor(timeout, unit)) {
            process.destroyForcibly()
            return null
        }
        return process.inputStream.bufferedReader().readText().trim().toIntOrNull()
    }

    /**
     * What PID 1 in the container actually is, read from the **host**.
     *
     * Not `docker exec cat /proc/1/cmdline`: the native image is distroless and has no `cat`, so a
     * check written that way cannot run on one of the two variants — and a check that cannot run on
     * half its subjects is not a check.
     */
    fun pid1(): String =
        docker("inspect", "-f", "{{json .Config.Entrypoint}}", requireId()).trim()

    fun logs(): String = runCatching { docker("logs", requireId()) }.getOrElse { "" }

    fun remove() {
        id?.let { runCatching { docker("rm", "-f", it) } }
        id = null
    }

    private fun requireId(): String = id ?: error("the container was not started")

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "docker ${args.joinToString(" ")} failed with $code:\n$output" }
        return output
    }
}
