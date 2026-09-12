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
 *     val run = runUntilSignal(deadlines) {
 *         announce(AnnounceNotReady(readiness))
 *         drain(EngineDrain(server, deadlines.drain, deadlines.drain + 5.seconds))
 *         pool(myPool)
 *     }
 *     println(run.transcript)
 * }
 * ```
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
    register: ShutdownPlanBuilder.() -> Unit = {},
): ShutdownRun =
    watch.use { installed ->
        val signal = installed.awaitSignal()
        val transcript = shutdownSequence(deadlines, register = register).run()
        // AFTER the sequence, always — including when a stage overran its deadline, because the
        // transcript records that and the process still has to be allowed to end. Before it would
        // mean racing the runtime against kore's own stages.
        installed.releaseProcess()
        ShutdownRun(signal, transcript)
    }
