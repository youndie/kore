package io.github.youndie.kore

/**
 * Enumeration goes through glibc's `__environ` — note the two leading underscores; the
 * POSIX-documented spelling `environ` is not what `platform.posix` exposes on these targets, and
 * writing it gives an error naming a missing symbol rather than a missing platform.
 */
public actual fun korePlatform(): KorePlatform = KorePlatform("linux")
