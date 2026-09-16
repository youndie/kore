package io.github.youndie.kore.oracle

import java.util.concurrent.TimeUnit

/**
 * What the oracle needs of the thing it is signalling — six methods, and deliberately no more.
 *
 * Everything else in the run is already transport agnostic: the drivers speak HTTP to a port, the
 * readiness poller speaks HTTP to a port, and every assertion reads `Observations` and nothing else.
 * So the one place a second kind of subject has to be understood is here, which is what makes
 * [LocalProcess] sixty lines rather than a second harness ([#85](https://github.com/youndie/kore/issues/85)).
 *
 * **Why a second kind at all.** kore's central finding is that `EmbeddedServer.stop` runs its steps in
 * the opposite order on the JVM and on Kotlin/Native, from identical source — so a consumer's JVM half
 * is exactly where a shutdown defect can hide that the native run cannot see. A service whose JVM
 * artefact is a distribution rather than an image had no way to be pointed at, and containerising one
 * for the test puts a test-only image in the repository that every clone then inherits.
 */
interface Subject {
    /** The port the drivers and the poller connect to on `127.0.0.1`. */
    val port: Int

    fun start()

    /** What is PID 1 — printed, because a shell wrapper that is not the process is how a signal goes missing. */
    fun pid1(): String

    fun sigterm()

    /** The exit code, or `null` when it did not exit inside the timeout. */
    fun awaitExit(timeout: Long, unit: TimeUnit): Int?

    /**
     * Whether it is still up.
     *
     * Asked while waiting for the subject to serve, so that a subject which **died** is reported as
     * having died. Without it the wait runs its full thirty seconds and says `never answered /health`
     * — which reads as a subject that will not start and hides why. That message has now hidden two
     * different failures: a required configuration key that was not supplied, and an executable path
     * that did not resolve.
     */
    fun isAlive(): Boolean

    fun remove()
}

/**
 * A subject that is a local process rather than a container — a distribution's start script, or a
 * `java -jar`.
 *
 * **The signal reaches the JVM, and that was checked rather than assumed.** A Gradle start script
 * ends in `exec "$JAVACMD" "$@"`, so the shell replaces itself and the pid the harness holds is the
 * JVM's. Without that line this transport would be sending `SIGTERM` to bash — the same defect the
 * sample's `Dockerfile` carries a paragraph about, one level out.
 *
 * **The port is given rather than discovered.** A container publishes a random host port and the
 * harness reads it back; a local process listens on whatever it was configured with, and nothing can
 * tell the harness that but the caller.
 */
class LocalProcess(
    private val command: List<String>,
    override val port: Int,
    private val env: Map<String, String> = emptyMap(),
) : Subject {
    private var process: Process? = null

    override fun start() {
        process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .apply { environment().putAll(env) }
                .start()
    }

    override fun pid1(): String = "${command.joinToString(" ")} (pid ${running().pid()})"

    /**
     * `destroy()` is `SIGTERM` on Unix, which is the whole point — `destroyForcibly` would be
     * `SIGKILL` and would measure nothing about an ordered shutdown.
     */
    override fun sigterm() {
        running().destroy()
    }

    override fun awaitExit(timeout: Long, unit: TimeUnit): Int? =
        if (running().waitFor(timeout, unit)) running().exitValue() else null

    override fun isAlive(): Boolean = process?.isAlive ?: false

    override fun remove() {
        process?.destroyForcibly()
        process = null
    }

    private fun running(): Process = process ?: error("the subject was not started")
}
