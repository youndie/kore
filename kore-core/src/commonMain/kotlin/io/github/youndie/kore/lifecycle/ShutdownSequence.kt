package io.github.youndie.kore.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The stop sequence, as a machine that records what it did.
 *
 * It knows nothing about HTTP, sockets or Ktor, and that is the design rather than an accident: a
 * machine reachable only through a running server is one whose orderings can be tested only by
 * scripting them, which is the class of test this library exists to distrust. Here it can be driven
 * directly, with generated durations and generated failures, by
 * `docs/research/research-oracle.md` §3.
 *
 * ## The bound on total time, stated exactly
 *
 * [run] returns no later than the sum of the stage deadlines **plus whatever the caller's own
 * dispatcher makes it wait**. It achieves that by refusing to *wait* for a participant past its
 * stage deadline, not by being able to stop one: each stage runs its participants in a child scope
 * and, on timeout, cancels that scope without joining it.
 *
 * So a participant that ignores cancellation keeps running while the sequence moves on. That is
 * deliberate and it is the honest trade. The alternative — structured concurrency, joining the
 * children — makes the bound a lie the first time a participant blocks in a way `withTimeout` cannot
 * interrupt, which on Kotlin/Native is not hypothetical (research Risk 3). A shutdown that overruns
 * its grace period is killed mid-drain; a leaked coroutine in a process that is exiting costs
 * nothing.
 */
public class ShutdownSequence(
    private val plan: List<StagePlan>,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        val order = plan.map { it.stage }
        require(order == order.distinct()) { "a stage appears twice in the plan: $order" }
        require(order == KoreStage.specifiedOrder.filter { it in order.toSet() }) {
            "the plan is not in the specified order: $order"
        }
    }

    private val lock = Mutex()
    private var running: CompletableDeferred<ShutdownTranscript>? = null

    /**
     * Runs the sequence, once.
     *
     * A second call — a second signal, or a signal beside a programmatic stop — does not run
     * anything: it waits for the run already in progress and returns the same transcript. Two
     * signals arriving a millisecond apart is the ordinary case, not the exotic one.
     */
    public suspend fun run(): ShutdownTranscript {
        val (result, mine) =
            lock.withLock {
                val existing = running
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<ShutdownTranscript>().also { running = it } to true
                }
            }

        if (!mine) return result.await()

        return try {
            execute().also { result.complete(it) }
        } catch (cancelled: CancellationException) {
            result.cancel(cancelled)
            throw cancelled
        }
    }

    private suspend fun execute(): ShutdownTranscript {
        val start = timeSource.markNow()
        val runs = ArrayList<StageRun>(plan.size)

        for (stagePlan in plan) {
            val enteredAt = start.elapsedNow()
            val failures = ArrayList<ParticipantFailure>()
            val outcome = runStage(stagePlan, failures)
            runs.add(
                StageRun(
                    stage = stagePlan.stage,
                    enteredAt = enteredAt,
                    leftAt = start.elapsedNow(),
                    outcome = outcome,
                    failures = failures.toList(),
                ),
            )
        }

        return ShutdownTranscript(runs.toList())
    }

    private suspend fun runStage(
        stagePlan: StagePlan,
        failures: MutableList<ParticipantFailure>,
    ): StageOutcome {
        if (stagePlan.participants.isEmpty()) {
            // A DWELL stage still spends its duration: the waiting IS the work. Anything else takes
            // no time when it has nothing to do.
            if (stagePlan.duration == StageDuration.DWELL) delay(stagePlan.deadline)
            return StageOutcome.COMPLETED
        }

        // The participants of a stage run concurrently and on Kotlin/Native that means they can run
        // on different threads, so the failure list has more than one writer and a plain `add` is a
        // data race. Guarded rather than reasoned about: the catch block is inside a coroutine and
        // may suspend, so a Mutex costs nothing and is correct on every target.
        val collecting = Mutex()

        // A child scope with a Job of its own rather than `coroutineScope { }`. Structured
        // concurrency would join the children on the way out, which is exactly the wait the deadline
        // exists to avoid — see the class comment.
        val scope = CoroutineScope(coroutineContext + Job())
        val jobs =
            stagePlan.participants.map { participant ->
                scope.launch {
                    try {
                        participant.stop()
                    } catch (cancelled: CancellationException) {
                        // The stage's deadline, not this participant's opinion. Never recorded as a
                        // failure and never swallowed: swallowing cancellation is how a coroutine
                        // that will not stop gets blamed on something else.
                        throw cancelled
                    } catch (failure: Throwable) {
                        collecting.withLock { failures.add(ParticipantFailure(participant.name, failure)) }
                    }
                }
            }

        val startedAt = timeSource.markNow()
        val finished = withTimeoutOrNull(stagePlan.deadline) { jobs.joinAll() }

        // A DWELL stage whose participants finished early still waits out the rest of its duration.
        // The announce stage flips a flag in microseconds and must then let the flip propagate.
        if (finished != null && stagePlan.duration == StageDuration.DWELL) {
            val remaining = stagePlan.deadline - startedAt.elapsedNow()
            if (remaining > Duration.ZERO) delay(remaining)
        }

        return if (finished == null) {
            scope.cancel("${stagePlan.stage}: deadline of ${stagePlan.deadline} exceeded")
            StageOutcome.DEADLINE_EXCEEDED
        } else {
            StageOutcome.COMPLETED
        }
    }

    public companion object {
        /** The plan with no participants anywhere — what a process with nothing registered still does. */
        public fun bare(deadlines: Map<KoreStage, Duration>): ShutdownSequence =
            ShutdownSequence(
                KoreStage.specifiedOrder.map { StagePlan(it, deadlines[it] ?: Duration.ZERO) },
            )
    }
}
