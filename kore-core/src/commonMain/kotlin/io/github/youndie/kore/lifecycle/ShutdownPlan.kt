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
    /**
     * How long the process will be given before it is killed.
     *
     * **`null` means kore was not told**, and it then assumes the Kubernetes default — decided in
     * B-25, not merely the hypothesis it started as.
     *
     * **The assumption errs optimistically, and that is the part to know.** 30 s is the largest
     * common budget, so where the real one is smaller kore's fit check passes a plan that will be
     * killed. `docker stop` defaults to **10 s** — measured, 11 s wall clock against a container that
     * ignores `SIGTERM` — while kore's own default deadlines add up to **29 s**. A service on kore's
     * defaults under plain `docker stop` is therefore `SIGKILL`ed in the middle of its drain, and
     * this check says nothing, because it was comparing against a number nobody gave it.
     *
     * kore cannot discover the real budget: nothing tells a process what it is, on any of the
     * platforms kore targets. So the assumption stays, it is **printed as an assumption** everywhere
     * it is used, and the answer for anyone outside Kubernetes is one line: declare it.
     */
    gracePeriod: Duration? = null,
) {
    /** The grace period in force, declared or assumed. */
    public val gracePeriod: Duration = gracePeriod ?: KUBERNETES_DEFAULT_GRACE_PERIOD

    /** Whether that number came from the deployment or from kore. Printed beside it. */
    public val gracePeriodWasDeclared: Boolean = gracePeriod != null

    /** What the whole sequence can cost. */
    public val total: Duration get() = preDrainWait + drain + releaseGroup * 3

    init {
        require(!preDrainWait.isNegative() && !drain.isNegative() && !releaseGroup.isNegative()) {
            "a deadline cannot be negative: preDrainWait=$preDrainWait drain=$drain releaseGroup=$releaseGroup"
        }
        // REFUSED AT STARTUP, where somebody is looking at both numbers — rather than discovered as a
        // SIGKILL in the middle of a drain, which in a log looks exactly like a crash. The message
        // names both, because the reader has to see which of the two they got wrong.
        require(total <= this.gracePeriod) {
            "the shutdown sequence needs $total and the grace period is ${this.gracePeriod}" +
                (if (this.gracePeriodWasDeclared) "" else " (assumed; kore was not told)") +
                ": preDrainWait=$preDrainWait + drain=$drain + 3 x releaseGroup=$releaseGroup. " +
                "Either shorten a deadline or raise terminationGracePeriodSeconds"
        }
    }

    /**
     * What `--print-config` shows: the sum, the grace period, and where each number came from.
     *
     * The **origin** is on the line for the same reason it is on every configuration value: "kore
     * assumed 30 s" and "the deployment said 30 s" are different facts, and only the second is
     * something anybody checked.
     */
    public fun describe(): String =
        buildString {
            appendLine("shutdown deadlines:")
            appendLine("  announce (wait)   $preDrainWait")
            appendLine("  drain             $drain — spent in full under load, not a ceiling")
            appendLine("  release, x3       $releaseGroup")
            appendLine("  total             $total")
            appendLine(
                "  grace period      $gracePeriod ${if (gracePeriodWasDeclared) "(declared)" else "(ASSUMED — kore was not told)"}",
            )
        }

    public companion object {
        /**
         * Kubernetes' own default for `terminationGracePeriodSeconds`, read in its documentation
         * (research §1.10) rather than recalled.
         */
        public val KUBERNETES_DEFAULT_GRACE_PERIOD: Duration = 30.seconds
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
    private val announcements = mutableListOf<ShutdownParticipant>()
    private val consumers = mutableListOf<ShutdownParticipant>()
    private val pools = mutableListOf<ShutdownParticipant>()
    private val telemetry = mutableListOf<ShutdownParticipant>()
    private var engine: ShutdownParticipant? = null

    /**
     * Runs at the start of the announce stage, before its wait.
     *
     * In practice exactly one thing goes here — [AnnounceNotReady] — and it is a registration rather
     * than a hard-wired line so the machine keeps knowing nothing about readiness.
     */
    public fun announce(participant: ShutdownParticipant) {
        announcements += participant
    }

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
            StagePlan(KoreStage.ANNOUNCE, deadlines.preDrainWait, announcements.toList(), StageDuration.DWELL),
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
