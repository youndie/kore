package io.github.youndie.kore.runtime

/**
 * Declared absent rather than discovered absent — the same rule the environment walk follows.
 *
 * Reading the paths would answer "unavailable" here too, by failing to open four files. Saying it
 * outright is a better message and, more to the point, it cannot turn into a silent "no limit" if
 * something ever mounts a directory of that name on a developer's laptop. macOS native is a
 * development target; the servers run on Linux.
 */
public actual fun containerMemoryBudget(): MemoryBudget =
    MemoryBudget.Unavailable("cgroups are a Linux facility and this binary is macos")
