package io.github.youndie.kore.lifecycle

import kotlin.time.Duration

/**
 * What one stage is asked to do, and how long it gets.
 *
 * **A deadline per stage, not one budget for the sequence.** A single overall timeout is a budget the
 * first stage can spend entirely: a dependency check that hangs or a flush against a dead broker
 * would leave the drain with nothing, and the symptom is a `SIGKILL` in the middle of a drain, which
 * in a log looks exactly like a crash.
 */
/** What a stage's duration means. */
public enum class StageDuration {
    /**
     * A bound on work. A stage with nothing to do takes no time — waiting out a pool-closing deadline
     * with no pools registered would be absurd.
     */
    DEADLINE,

    /**
     * A duration to **spend**, whether or not there is work.
     *
     * Exactly one stage is like this and it is the announce stage, whose entire job is to wait: what
     * stops traffic arriving after `SIGTERM` is the time a rule change takes to reach every node
     * (research §1.10), not the flag flip that precedes it.
     *
     * It exists because the machine did the obvious thing — return at once from a stage with no
     * participants — and thereby deleted the wait. A test asked for seven seconds and got five
     * microseconds. The announce stage is the one most likely to be optimised away by somebody who
     * has not read why it is there, and this is the type saying so.
     */
    DWELL,
}

public class StagePlan(
    public val stage: KoreStage,
    public val deadline: Duration,
    /**
     * Run **concurrently** within the stage. Several consumers flushing at once is the point; one of
     * them being slow must not serialise the rest into the deadline.
     */
    public val participants: List<ShutdownParticipant> = emptyList(),
    /** See [StageDuration]. Everything but the announce stage bounds work rather than spending time. */
    public val duration: StageDuration = StageDuration.DEADLINE,
) {
    init {
        require(!deadline.isNegative()) { "$stage: a deadline cannot be negative" }
    }
}
