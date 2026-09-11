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
public class StagePlan(
    public val stage: KoreStage,
    public val deadline: Duration,
    /**
     * Run **concurrently** within the stage. Several consumers flushing at once is the point; one of
     * them being slow must not serialise the rest into the deadline.
     */
    public val participants: List<ShutdownParticipant> = emptyList(),
) {
    init {
        require(!deadline.isNegative()) { "$stage: a deadline cannot be negative" }
    }
}
