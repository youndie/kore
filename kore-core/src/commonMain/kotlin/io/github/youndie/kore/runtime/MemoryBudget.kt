package io.github.youndie.kore.runtime

/**
 * What the process is allowed to use, as the kernel will enforce it.
 *
 * Three cases and not two, for the reason the rest of this library gives an absent capability its
 * own answer: *"no limit"* and *"nobody could tell"* are different facts, and a deployment that
 * reads the second as the first has been told it has room it does not have.
 */
public sealed interface MemoryBudget {
    /** A limit the kernel will kill the process for exceeding, with the file that stated it. */
    public data class Bounded(
        public val bytes: Long,
        /** The file this came from, so a wrong number can be traced without guessing. */
        public val source: String,
    ) : MemoryBudget

    /** A cgroup exists and states no limit — `max` on v2, the page-aligned `LONG_MAX` on v1. */
    public data class Unbounded(public val source: String) : MemoryBudget

    /** Nothing could be read, and the reason says what was tried. Never rendered as "no limit". */
    public data class Unavailable(public val reason: String) : MemoryBudget
}

/**
 * The memory limit this process is running under, read from the cgroup the kernel put it in.
 *
 * **Kotlin/Native does not do this for you.** The JVM has read the container limit since 10
 * (`UseContainerSupport`), and a Kotlin/Native binary has no equivalent: its GC target heap is
 * whatever the compiler defaulted to or whatever a human typed, and neither number knows what the
 * chart says. That gap is the reason this function exists — see
 * [`docs/features/feature-memory-budget.md`](https://github.com/youndie/kore/blob/main/docs/features/feature-memory-budget.md).
 *
 * It **reads** and does not act. Nothing in kore changes a GC setting on its own: what a heap target
 * should be under a given limit is an open measurement, and a library that guessed it would be
 * guessing on every consumer's behalf at once.
 */
public expect fun containerMemoryBudget(): MemoryBudget

/** One line for a log or `--print-config`: a number a person can compare with the chart. */
public fun MemoryBudget.render(): String =
    when (this) {
        is MemoryBudget.Bounded -> "${formatBytes(bytes)} ($source)"
        is MemoryBudget.Unbounded -> "no limit ($source)"
        // The word is "unknown", never "none": this branch is the one a reader must not mistake for
        // the one above it.
        is MemoryBudget.Unavailable -> "unknown — $reason"
    }

internal fun formatBytes(bytes: Long): String {
    val mib = 1024L * 1024L
    return when {
        bytes >= 1024L * mib && bytes % (1024L * mib) == 0L -> "${bytes / (1024L * mib)} GiB"
        bytes >= mib -> "${bytes / mib} MiB"
        else -> "$bytes B"
    }
}
