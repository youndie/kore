package io.github.youndie.kore.runtime

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * `maxHeapBytes`, and **not** `targetHeapBytes` — measured, see the feature document.
 *
 * The obvious call is the target, and it does not hold: with `GC.autotune` on, which is the default,
 * the runtime recomputes the target after every collection from the live set, and a value written
 * before the first GC is gone by the second. `maxHeapBytes` is the bound the autotuner is not
 * allowed to cross, which is what "this container will be killed above N" actually means.
 */
@OptIn(NativeRuntimeApi::class)
public actual fun applyHeapCeiling(bytes: Long): HeapCeiling {
    require(bytes > 0) { "a heap ceiling of $bytes bytes is not a ceiling" }
    GC.maxHeapBytes = bytes
    // Read back rather than echo. A setting the runtime clamped or ignored is a setting the caller
    // should log as it ended up, not as it was requested.
    return HeapCeiling(
        applied = GC.maxHeapBytes == bytes,
        ceilingBytes = GC.maxHeapBytes,
        explanation = "GC.maxHeapBytes, with autotune left as it was",
    )
}

@OptIn(NativeRuntimeApi::class)
public actual fun heapCeiling(): Long? = GC.maxHeapBytes
