package io.github.youndie.kore.runtime

/** The target this library exists for: the cgroup is read, and both layouts are tried. */
public actual fun containerMemoryBudget(): MemoryBudget = resolveMemoryBudget(::readSmallFile)
