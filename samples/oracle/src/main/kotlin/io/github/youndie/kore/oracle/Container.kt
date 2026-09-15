package io.github.youndie.kore.oracle

import java.util.concurrent.TimeUnit

/**
 * The subject, driven from outside through `docker`.
 *
 * Outside on purpose: the run must observe a process it does not share a runtime with, and the
 * signal must land on PID 1 the way a kubelet sends it. Anything the harness could learn by being
 * inside the process is something it would be trusting the subject to tell it.
 */
class Container(
    private val image: String,
    private val subjectArgs: List<String> = emptyList(),
    /**
     * The subject's environment.
     *
     * Needed since B-50: the kore arm declares a configuration schema with a **required** key, so it
     * does not start unconfigured — which is the feature, not an obstacle. A harness that could only
     * pass arguments could not measure a service that reads its configuration the way a deployment
     * supplies it.
     */
    private val env: Map<String, String> = emptyMap(),
    /**
     * Flags for `docker run` itself rather than for the subject.
     *
     * Added for the memory budget (B-52): the only way to give a process a cgroup limit is to ask
     * the runtime for one, and the whole question is what the process reads back from inside. A
     * harness that could only set environment variables could not pose it at all.
     */
    private val runArgs: List<String> = emptyList(),
) {
    private var id: String? = null

    /** The host port the container's 8080 was published on. */
    var port: Int = 0
        private set

    /**
     * Retries the bind, because the ephemeral port docker picks can already be taken.
     *
     * A run that starts containers back to back will eventually be handed a host port still held by
     * a socket in `TIME_WAIT`, and `docker run` fails with *"address already in use"*. That is the
     * harness colliding with itself; reading it as a result would be reading the stand instead of the
     * subject. Retried a few times and then given up on loudly — a retry that hides a real inability
     * to start would be the same mistake in the other direction.
     */
    fun start() {
        var lastFailure: Throwable? = null
        repeat(START_ATTEMPTS) { attempt ->
            // A port BELOW the ephemeral range, not `-p 0`, and that is the fix rather than the
            // retry. Asking docker for any free port makes it choose inside
            // `net.ipv4.ip_local_port_range` (32768–60999 here) — the same range the harness's own
            // outgoing connections draw their source ports from. Under a run that opens connections
            // quickly the two collide and `docker run` fails with "address already in use", which
            // reads like the subject failing to start. Choosing from 20000–30000 takes the harness
            // out of its own way.
            val chosen = 20_000 + kotlin.random.Random.nextInt(10_000)
            val started =
                runCatching {
                    val envArgs = env.flatMap { (key, value) -> listOf("-e", "$key=$value") }
                    id = docker(
                        *(
                            listOf("run", "-d", "-p", "$chosen:8080") + runArgs + envArgs +
                                image + subjectArgs
                        ).toTypedArray(),
                    ).trim()
                    port = chosen
                }
            if (started.isSuccess) return
            lastFailure = started.exceptionOrNull()
            // The container may exist even when the port bind failed; it would otherwise be left
            // behind holding a name and an image reference.
            remove()
            Thread.sleep(500L * (attempt + 1))
        }
        throw IllegalStateException("could not start $image after $START_ATTEMPTS attempts", lastFailure)
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

    /**
     * The container's PID **on the host**, and then its `VmRSS` from the host's own `/proc`.
     *
     * Not `docker stats`, which reports the cgroup's memory including page cache — a number that
     * answers "how much has this container touched" rather than "how much is resident for this
     * process". And not from inside: the native image is distroless and has nothing to read it with,
     * so a measurement written that way could not be taken on half the subjects.
     *
     * Returns `null` when `/proc` does not have it — a container that has already exited, or a
     * daemon that is not on this host. A missing number is reported as missing rather than as zero.
     */
    fun rssKb(): Long? {
        val pid = docker("inspect", "-f", "{{.State.Pid}}", requireId()).trim().toIntOrNull() ?: return null
        val status = java.io.File("/proc/$pid/status")
        if (!status.isFile) return null
        return status.readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter("VmRSS:")
            ?.trim()
            ?.removeSuffix(" kB")
            ?.trim()
            ?.toLongOrNull()
    }

    fun logs(): String = runCatching { docker("logs", requireId()) }.getOrElse { "" }

    /** The container's exit code, or `-1` while it is still running. */
    fun exitCode(): Int =
        runCatching { docker("inspect", "-f", "{{.State.ExitCode}}", requireId()).trim().toInt() }.getOrElse { -1 }

    /** Whether it is still up — which is how a refusal is told from a start (B-50). */
    fun isRunning(): Boolean =
        runCatching { docker("inspect", "-f", "{{.State.Running}}", requireId()).trim() == "true" }.getOrElse { false }

    fun remove() {
        id?.let { runCatching { docker("rm", "-f", it) } }
        id = null
    }

    private companion object {
        const val START_ATTEMPTS = 5
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
