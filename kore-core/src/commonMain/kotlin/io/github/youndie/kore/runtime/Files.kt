package io.github.youndie.kore.runtime

/**
 * Reads a small text file, or answers null when it does not exist or cannot be read.
 *
 * `internal`, and the only thing the platforms have to supply for [containerMemoryBudget]: every
 * decision about what the content means is in `CgroupMemory.kt`, where a common test can reach it.
 */
internal expect fun readSmallFile(path: String): String?
