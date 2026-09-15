package io.github.youndie.kore.runtime

/**
 * Not available, and deliberately not emulated.
 *
 * A JVM's maximum heap is fixed at startup — `-Xmx`, or `MaxRAMPercentage` against the container
 * limit the JVM reads for itself. There is no runtime setter to call, and a call that quietly did
 * nothing would be worse than one that says so.
 */
public actual fun applyHeapCeiling(bytes: Long): HeapCeiling =
    HeapCeiling(
        applied = false,
        ceilingBytes = Runtime.getRuntime().maxMemory(),
        explanation = "the JVM fixes its maximum heap at startup; set -XX:MaxRAMPercentage or -Xmx instead",
    )

public actual fun heapCeiling(): Long? = Runtime.getRuntime().maxMemory()
