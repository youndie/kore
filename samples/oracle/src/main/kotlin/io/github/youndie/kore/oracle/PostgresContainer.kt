package io.github.youndie.kore.oracle

import java.util.concurrent.TimeUnit

/**
 * A Postgres the check can **stop underneath a live pool**, which is the whole point of it being a
 * container rather than a fixture.
 *
 * Separate from [Container] because it is a dependency rather than a subject: it is never sent a
 * signal, it is never asserted about, and it exists so that something else can be observed losing it.
 */
class PostgresContainer(private val image: String = "postgres:17-alpine") {
    private var id: String? = null

    var port: Int = 0
        private set

    fun start() {
        // A port below the ephemeral range, for the reason the subject container has one: asking
        // docker for any free port makes it choose from the same range the harness's own outgoing
        // connections draw from, and they collide.
        val chosen = 20_000 + kotlin.random.Random.nextInt(10_000)
        id =
            docker(
                "run", "-d", "-p", "$chosen:5432",
                "-e", "POSTGRES_USER=kore",
                "-e", "POSTGRES_PASSWORD=kore",
                "-e", "POSTGRES_DB=kore",
                image,
            ).trim()
        port = chosen
    }

    /**
     * A clean shutdown: the server closes its connections and the client is **told**.
     *
     * This is the case a pool notices most easily, not least — the peer sends `FIN`. Naming it that
     * way round is the correction to a comment that said the opposite and to an experiment built on
     * it.
     */
    fun stopServer() {
        docker("stop", requireId())
    }

    /**
     * The far end gone **without telling anyone**: the process is frozen, the TCP connections stay
     * open, and nothing ever answers.
     *
     * This is the state research §1.9 consequence 1 is about — *"a pool can hand out a connection
     * from its idle set without the server on the far end being alive"* — and it is not what a clean
     * `stop` produces. A network partition and a wedged server look like this; a shutdown does not.
     */
    fun pauseServer() {
        docker("pause", requireId())
    }

    fun unpauseServer() {
        runCatching { docker("unpause", requireId()) }
    }

    fun remove() {
        id?.let { runCatching { docker("rm", "-f", it) } }
        id = null
    }

    private fun requireId(): String = id ?: error("the postgres container was not started")

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "docker ${args.toList()} did not finish" }
        check(process.exitValue() == 0) { "docker ${args.joinToString(" ")} failed:\n$output" }
        return output
    }
}
