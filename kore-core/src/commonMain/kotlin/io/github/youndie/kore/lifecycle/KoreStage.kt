package io.github.youndie.kore.lifecycle

/**
 * The stop sequence, as a value.
 *
 * The order of these entries **is** the specification of
 * `docs/features/feature-ordered-shutdown.md` §3. Nothing chooses it at runtime and nothing
 * configures it: a sequence that can be reordered by configuration is a sequence whose order is not
 * a promise.
 *
 * ## Why seven and not five
 *
 * The feature document tells the story in five stages, with `release` holding three groups. That
 * reads well and is ambiguous about one thing the machine cannot be ambiguous about: **the unit that
 * carries a deadline**. `release` has three of them, one per group, so as a single stage it would
 * either need a deadline it does not have or three deadlines it cannot express.
 *
 * The property test asserts that the recorded transitions are a *prefix of this list*, which needs
 * one unambiguous list. So the three release groups are stages here, and the five-stage story stays
 * the human-facing grouping. Found by implementing; the document now says both.
 */
public enum class KoreStage {
    /**
     * The signal arrived. A marker with no work in it: the handler that gets us here sets a flag and
     * wakes a parked coroutine, because allocation, locks and `runBlocking` are not
     * async-signal-safe (research §1.3).
     *
     * It is a named stage anyway. It is where the two platforms differ most, and naming it is what
     * lets a test say "the sequence began exactly once" about something a signal can deliver twice.
     */
    SIGNAL,

    /**
     * Readiness goes false, then the process waits.
     *
     * The wait is the part that does the work. During a rollout the control plane has already marked
     * the terminating endpoint `ready: false` on its own (research §1.10); what flipping readiness
     * buys is the truth for anything polling the pod directly, and what stops traffic arriving after
     * `SIGTERM` is the time a rule change takes to reach every node.
     */
    ANNOUNCE,

    /** Accept stops, in-flight finishes, new arrivals get `503` with `Connection: close`. */
    DRAIN,

    /**
     * Consumers: flush, then close. Before the pools, because a consumer mid-handler still needs one.
     *
     * Flush and close are two verbs and the first is the one everyone omits — and the two published
     * booblik producers disagree about whether `close()` sends what it holds (research §1.8).
     */
    RELEASE_CONSUMERS,

    /** Connection pools close. After the consumers that were still using them. */
    RELEASE_POOLS,

    /** Telemetry flushes what can be flushed. Last, so it observes everything above it. */
    RELEASE_TELEMETRY,

    /**
     * The sequence finished. A marker, like [SIGNAL]: the machine does not end the process, because
     * a library that calls `exit()` is one a test cannot run twice.
     */
    EXIT,
    ;

    public companion object {
        /** The specified order. A transcript is always a prefix of this. */
        public val specifiedOrder: List<KoreStage> = entries.toList()
    }
}
