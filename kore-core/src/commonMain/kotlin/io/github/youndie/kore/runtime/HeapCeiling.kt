package io.github.youndie.kore.runtime

/** What a call to [applyHeapCeiling] did, so that a service can log it instead of assuming it. */
public class HeapCeiling(
    /** False on a target with no such setting; the explanation says which. */
    public val applied: Boolean,
    /** What the runtime reports after the call — not what was asked for. */
    public val ceilingBytes: Long?,
    public val explanation: String,
) {
    override fun toString(): String = "HeapCeiling(applied=$applied, ceiling=$ceilingBytes, $explanation)"
}

/**
 * Tells the Kotlin/Native GC it may not grow the heap past [bytes].
 *
 * **Nothing in kore calls this.** The budget is a fact and this is a policy: what fraction of a
 * container limit should be heap depends on how much of the process is *not* heap — the allocator's
 * per-thread pages, the Rust half of a database driver, glibc's arenas — and on this platform that
 * remainder is usually the larger term. A library that picked a fraction would be picking it for
 * every consumer at once, with none of their measurements.
 *
 * So: kore hands a service the number the kernel will kill it on ([containerMemoryBudget]) and this
 * one call to act on it. The fraction stays with the service that measured it.
 */
public expect fun applyHeapCeiling(bytes: Long): HeapCeiling

/** The current ceiling, or null on a target that has none. */
public expect fun heapCeiling(): Long?
