package io.github.youndie.kore.runtime

/**
 * The same reading on the JVM, and it is **not** redundant with what the JVM does for itself.
 *
 * A modern JVM sizes its own heap from the container limit (`UseContainerSupport`, on by default
 * since 10), and that is a heap. This answers the question the deployment asks — *what is the
 * process allowed to use in total* — which includes the metaspace, the thread stacks, the direct
 * buffers and whatever a native library allocates. The two numbers are not the same and only one of
 * them is what the kernel kills on.
 *
 * On anything but Linux there is no cgroup to read, and that is said rather than discovered.
 */
public actual fun containerMemoryBudget(): MemoryBudget {
    val os = System.getProperty("os.name").orEmpty()
    if (!os.startsWith("Linux", ignoreCase = true)) {
        return MemoryBudget.Unavailable("cgroups are a Linux facility and this JVM runs on $os")
    }
    return resolveMemoryBudget(::readSmallFile)
}
