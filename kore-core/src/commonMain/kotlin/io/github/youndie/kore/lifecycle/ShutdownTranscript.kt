package io.github.youndie.kore.lifecycle

import kotlin.time.Duration

/** Why a stage ended. */
public enum class StageOutcome {
    /** Every participant returned inside the deadline. Failures may still have been recorded. */
    COMPLETED,

    /** The deadline ran out first. The stage ended anyway; the next one began on time. */
    DEADLINE_EXCEEDED,
}

/** A participant that threw. Recorded rather than propagated: a half-run sequence leaks what it exists to close. */
public class ParticipantFailure(
    public val participant: String,
    public val failure: Throwable,
) {
    override fun toString(): String = "$participant: ${failure::class.simpleName}: ${failure.message}"
}

/** One stage, as it actually happened. */
public class StageRun(
    public val stage: KoreStage,
    /** From the sequence's start, so two runs are comparable without sharing a clock origin. */
    public val enteredAt: Duration,
    public val leftAt: Duration,
    public val outcome: StageOutcome,
    public val failures: List<ParticipantFailure>,
) {
    public val took: Duration get() = leftAt - enteredAt

    override fun toString(): String =
        "$stage $outcome in $took" + if (failures.isEmpty()) "" else " ${failures.size} failure(s)"
}

/**
 * What the sequence did, in order — the thing every property in
 * `docs/research/research-oracle.md` §3.2 is asserted over.
 *
 * It exists because the alternative is asserting over a lambda in `ApplicationStopping`, which
 * cannot be done. The machine was written before any stage did real work for exactly this reason: a
 * sequence given observability afterwards is a sequence whose test was written to match it.
 */
public class ShutdownTranscript(
    public val stages: List<StageRun>,
) {
    /** The stages in the order they ran. Property 1 asserts this is a prefix of [KoreStage.specifiedOrder]. */
    public val order: List<KoreStage> get() = stages.map { it.stage }

    /** Wall time from entering the first stage to leaving the last. Property 5 bounds this. */
    public val took: Duration
        get() = if (stages.isEmpty()) Duration.ZERO else stages.last().leftAt - stages.first().enteredAt

    public val failures: List<ParticipantFailure> get() = stages.flatMap { it.failures }

    public operator fun get(stage: KoreStage): StageRun? = stages.firstOrNull { it.stage == stage }

    override fun toString(): String = stages.joinToString("\n")
}
