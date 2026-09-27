package io.github.youndie.kore.ktor

/** A port the kernel has just handed out and that is already closed — synchronously — on return. */
internal expect fun freePort(): Int
