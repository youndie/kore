package io.github.youndie.kore.lifecycle

import io.github.youndie.kore.signal.ShutdownSignal
import io.github.youndie.kore.signal.ShutdownSignalWatch
import io.github.youndie.kore.signal.installShutdownSignalWatch

/** What one shutdown was: which signal asked for it, and what the stages did. */
public class ShutdownRun(
    public val signal: ShutdownSignal,
    public val transcript: ShutdownTranscript,
)

/**
 * Wait for a signal, run the sequence, let the process go.
 *
 * The three steps between a running server and an exited process that are **kore's own** rather than
 * the service's. What stays the caller's is the one thing no API can supply: which participants to
 * register, in [register].
 *
 * ```kotlin
 * server.start(wait = false)
 * startup.markStarted()
 * runBlocking {
 *     runUntilSignal(
 *         deadlines,
 *         // NOT after the call: on the JVM this function returning means the shutdown hook has
 *         // returned, and the process is already terminating. See "where the transcript goes".
 *         onFinished = { run -> println(run.transcript) },
 *     ) {
 *         announce(AnnounceNotReady(readiness))
 *         drain(EngineDrain(server, deadlines.drain, deadlines.drain + 5.seconds))
 *         pool(myPool)
 *     }
 * }
 * ```
 *
 * ## Where the transcript goes, and why not after the call
 *
 * The example above used to end with `println(run.transcript)` **after** `runUntilSignal`, and on the
 * JVM that line does not run. `JvmShutdownSignalWatch` is a shutdown hook whose own KDoc says the
 * hook thread must not return until the sequence is done, because returning from it is what lets the
 * JVM exit — so `releaseProcess()` releases the runtime and the main thread then races termination.
 *
 * The first consumer measured it over several runs: the release stage's effects (a pool logging its
 * own shutdown) appeared **every** time, and the line after the call appeared **never**
 * ([#59](https://github.com/youndie/kore/issues/59)). Native has no such hook and carries on, which
 * is exactly the asymmetry that makes this easy to miss on the platform kore is designed for — and
 * exactly the class of defect this library exists to remove, reintroduced in its own documented
 * example.
 *
 * [onFinished] runs inside the window the hook still holds. A callback that throws is reported, but
 * only after the process has been released: it must not be able to hold a process that was asked to
 * stop.
 *
 * ## Why this exists and an entry point does not
 *
 * B-30 decided kore does not own `main`: a real service's entry point runs migrations, chooses its engine and composes its own DI
 * before any route exists, and accommodating that would make kore a framework. What it *does* own is
 * the stretch from the signal to the exit — and a consumer that gets one of those three steps wrong
 * gets a shutdown that looks like it works:
 *
 * * `start(wait = true)` instead of `false` — the main thread never reaches the await, so the
 *   sequence never runs and the process is killed at the grace period;
 * * no `releaseProcess()` — on the JVM the runtime waits out the bounded timeout and exits anyway,
 *   so the cost is latency nobody attributes to a missing call;
 * * the watch installed before the server is serving — a signal arriving in that window is caught by
 *   a handler whose sequence has nothing to drain.
 *
 * **`suspend`, not blocking.** The caller supplies `runBlocking` because the caller owns its thread —
 * a library that blocks one it was not given is choosing for a process it does not own. It is also
 * what keeps this testable: the test drives it on a virtual clock, which a `runBlocking` inside would
 * make impossible.
 *
 * **The pieces stay public.** A service with its own signal handling, or one that runs the sequence
 * for a reason that is not a signal, keeps using `installShutdownSignalWatch` and `shutdownSequence`
 * directly — which is also how the property test drives the machine with no signal anywhere near it.
 *
 * @param watch defaulted, and installing it is a side effect: the default installs a handler at the
 *   moment of the call, which is why the call belongs after the server is serving. Pass your own to
 *   control that.
 */
public suspend fun runUntilSignal(
    deadlines: ShutdownDeadlines = ShutdownDeadlines(),
    watch: ShutdownSignalWatch = installShutdownSignalWatch(),
    onFinished: suspend (ShutdownRun) -> Unit = {},
    register: ShutdownPlanBuilder.() -> Unit = {},
): ShutdownRun =
    watch.use { installed ->
        val signal = installed.awaitSignal()
        val transcript = shutdownSequence(deadlines, register = register).run()
        val run = ShutdownRun(signal, transcript)

        // BEFORE the release, and that is the whole reason this parameter exists. On the JVM the
        // watch is a shutdown hook, and `releaseProcess` is what lets the hook thread return — after
        // which the runtime is terminating and everything the caller writes below this call is
        // racing it. Measured from the first consumer: the release stage's effects appeared in every
        // run and the `println` immediately after `runUntilSignal` appeared in none (#59).
        val failure = runCatching { onFinished(run) }.exceptionOrNull()

        // AFTER the sequence, always — including when a stage overran its deadline, because the
        // transcript records that and the process still has to be allowed to end. Before it would
        // mean racing the runtime against kore's own stages.
        installed.releaseProcess()

        // Rethrown only after the release. A callback that throws must not be able to hold a process
        // that has been asked to stop — but swallowing it would hide a failure in the one place
        // nobody is watching, so it is both released and reported.
        failure?.let { throw it }
        run
    }
