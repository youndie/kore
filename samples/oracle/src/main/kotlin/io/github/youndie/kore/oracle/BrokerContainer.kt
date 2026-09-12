package io.github.youndie.kore.oracle

import java.util.concurrent.TimeUnit

/** A real booblik, from the image booblik itself publishes on every tag. */
class BrokerContainer(
    private val topic: String = "kore-b45",
    private val image: String = "ghcr.io/youndie/booblik:latest",
) {
    private var id: String? = null

    var port: Int = 0
        private set

    fun start() {
        // Below the ephemeral range, for the reason the other containers here have one: asking docker
        // for any free port makes it choose from the range the harness's own connections draw from.
        val chosen = 20_000 + kotlin.random.Random.nextInt(10_000)
        // The broker does NOT create topics — `BooblikConfig.topics` is fixed at startup (booblik
        // M-42). A producer against a topic nobody declared is refused, and the first *fetch* of one
        // costs the connection rather than an error code, which is how this was found: an EOF from a
        // socket that had just been opened.
        id = docker("run", "-d", "-p", "$chosen:9092", "-e", "BOOBLIK_TOPICS=$topic:1", image).trim()
        port = chosen
    }

    /** What the broker itself said — the half of a failure the client cannot see. */
    fun logs(): String = id?.let { runCatching { docker("logs", it) }.getOrElse { e -> "docker logs failed: $e" } } ?: "no container"

    fun state(): String = id?.let { runCatching { docker("inspect", "-f", "{{.State.Status}} exit={{.State.ExitCode}}", it) }.getOrElse { e -> "$e" } } ?: "no container"

    fun remove() {
        id?.let { runCatching { docker("rm", "-f", it) } }
        id = null
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "docker ${args.toList()} did not finish" }
        check(process.exitValue() == 0) { "docker ${args.joinToString(" ")} failed:\n$output" }
        return output
    }
}
