package io.github.youndie.kore.lifecycle

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/**
 * The deadlines, in one place, each with its derivation.
 *
 * Every number here is derived rather than chosen — `docs/features/feature-health-probes.md` §4 has
 * the arithmetic. A default that is wrong is worse than no default, because nobody reads it twice.
 */
public class ShutdownDeadlines(
    /**
     * How long the process keeps serving after readiness has gone false.
     *
     * The floor is Kubernetes': a readiness period times the failure threshold, plus the time a rule
     * change takes to reach every node. **Five seconds is an estimate, not a measurement** — it is
     * written as a hypothesis in the feature document, and B-20 is its address.
     */
    public val preDrainWait: Duration = 5.seconds,
    /**
     * How long the engine gets.
     *
     * **Not a ceiling.** CIO waits for the connectors' jobs and a keep-alive client keeps those
     * alive, so this is spent in full whenever anything is still connected — read it as "how long
     * shutdown takes under load" (research §1.13, measured).
     */
    public val drain: Duration = 15.seconds,
    /** Per release group. Three groups, so three of these in the worst case. */
    public val releaseGroup: Duration = 3.seconds,
) {
    /** What the whole sequence can cost. Compared against the grace period by B-12. */
    public val total: Duration get() = preDrainWait + drain + releaseGroup * 3

    init {
        require(!preDrainWait.isNegative() && !drain.isNegative() && !releaseGroup.isNegative()) {
            "a deadline cannot be negative: preDrainWait=$preDrainWait drain=$drain releaseGroup=$releaseGroup"
        }
    }
}

/**
 * Assembles the sequence a consumer actually gets.
 *
 * The three release groups are separate stages and their **order is not configurable**: consumers
 * before pools, because a consumer mid-handler still needs one; telemetry last, because it is the
 * only group whose job is to report on the others. A sequence whose order can be changed by
 * configuration is a sequence whose order is not a promise.
 */
public class ShutdownPlanBuilder internal constructor() {
    private val consumers = mutableListOf<ShutdownParticipant>()
    private val pools = mutableListOf<ShutdownParticipant>()
    private val telemetry = mutableListOf<ShutdownParticipant>()
    private var engine: ShutdownParticipant? = null

    /** Things holding a position or a socket: flush, then close. Before the pools they use. */
    public fun consumer(participant: ShutdownParticipant) {
        consumers += participant
    }

    /** Connection pools. After the consumers that were still using them. */
    public fun pool(participant: ShutdownParticipant) {
        pools += participant
    }

    /** Flushes what can be flushed. Last, so it observes everything above it. */
    public fun telemetry(participant: ShutdownParticipant) {
        telemetry += participant
    }

    /**
     * The HTTP engine. At most one, and deliberately not a `consumer`: it **is** the drain stage, and
     * putting it in a release group would run it after the things the requests it is draining need.
     */
    public fun drain(participant: ShutdownParticipant) {
        check(engine == null) { "the drain stage already has a participant: ${engine?.name}" }
        engine = participant
    }

    internal fun build(deadlines: ShutdownDeadlines): List<StagePlan> =
        listOf(
            StagePlan(KoreStage.SIGNAL, Duration.ZERO),
            StagePlan(KoreStage.ANNOUNCE, deadlines.preDrainWait, duration = StageDuration.DWELL),
            StagePlan(KoreStage.DRAIN, deadlines.drain, listOfNotNull(engine)),
            StagePlan(KoreStage.RELEASE_CONSUMERS, deadlines.releaseGroup, consumers.toList()),
            StagePlan(KoreStage.RELEASE_POOLS, deadlines.releaseGroup, pools.toList()),
            StagePlan(KoreStage.RELEASE_TELEMETRY, deadlines.releaseGroup, telemetry.toList()),
            StagePlan(KoreStage.EXIT, Duration.ZERO),
        )
}

/**
 * Builds the sequence.
 *
 * **Every stage is present even with no participants**, so a process that registered nothing still
 * produces the full transcript — and a stage missing from a transcript is then a defect rather than
 * an absence somebody has to reason about.
 */
public fun shutdownSequence(
    deadlines: ShutdownDeadlines = ShutdownDeadlines(),
    /**
     * Injectable so a test's transcript is stamped from the same clock its delays run on. Left at the
     * default under `runTest`, the delays are virtual and the marks are real, and a stage that waited
     * three seconds is recorded as having taken four milliseconds — which is a test failing against
     * a mechanism that works.
     */
    timeSource: TimeSource = TimeSource.Monotonic,
    register: ShutdownPlanBuilder.() -> Unit = {},
): ShutdownSequence = ShutdownSequence(ShutdownPlanBuilder().apply(register).build(deadlines), timeSource)
