package io.github.youndie.kore.runtime

/**
 * The cgroup reading itself, with the filesystem behind one function so that every branch of it is
 * a common test rather than a container.
 *
 * The platform actuals supply nothing but "read this file or answer null". Everything that can be
 * wrong about the answer — which cgroup the process is in, which of two layouts the host uses, an
 * ancestor that is stricter than the leaf — is decided here, once, for all four targets.
 */
internal const val CGROUP_ROOT: String = "/sys/fs/cgroup"

/**
 * cgroup v1 spells "no limit" as a number: `LONG_MAX` rounded down to a page. Anything at or above
 * it is the absence of a limit rather than nine exabytes of memory.
 */
internal const val V1_UNLIMITED: Long = 9223372036854771712L

internal fun resolveMemoryBudget(read: (String) -> String?): MemoryBudget {
    val tried = mutableListOf<String>()

    fun readTracked(path: String): String? {
        tried += path
        return read(path)
    }

    val self = readTracked("/proc/self/cgroup")

    // v2 FIRST, and the default path is the root rather than "give up".
    //
    // Inside a container with a cgroup namespace — the normal case under Docker and Kubernetes —
    // `/proc/self/cgroup` reads `0::/`, and the limit the kernel enforces is at the root of the
    // mount. Without the namespace, on a host looking at its own processes, the path is the nested
    // one and the root file says `max`. Reading only one of the two answers one of the two
    // situations, and the wrong answer in the other is a confident "no limit".
    val v2 =
        scan(
            paths = ancestors(self?.let(::cgroupV2Path) ?: "/").map { "$CGROUP_ROOT$it/memory.max".clean() },
            read = ::readTracked,
            parse = ::parseV2,
        )
    if (v2 != null) return v2

    // v1, where the controller has a mount of its own and the process's path comes from the line
    // that names `memory` among its controllers.
    val v1 =
        scan(
            paths =
                ancestors(self?.let(::cgroupV1MemoryPath) ?: "/")
                    .map { "$CGROUP_ROOT/memory$it/memory.limit_in_bytes".clean() },
            read = ::readTracked,
            parse = ::parseV1,
        )
    if (v1 != null) return v1

    return MemoryBudget.Unavailable("read none of ${tried.joinToString(", ")}")
}

/**
 * Every readable file in the chain, not the first one.
 *
 * A pod's limit is set on the container's own cgroup, but a limit on **any** ancestor is equally
 * enforced — that is what a pod-level limit over several containers is. The tightest one is the one
 * the kernel kills on, so the smallest bounded value wins; "unbounded" is only the answer when every
 * file in the chain said so.
 */
private fun scan(
    paths: List<String>,
    read: (String) -> String?,
    parse: (String) -> MemoryBudget?,
): MemoryBudget? {
    var tightest: MemoryBudget.Bounded? = null
    var unbounded: MemoryBudget.Unbounded? = null
    paths.forEach { path ->
        when (val parsed = read(path)?.let(parse)) {
            is MemoryBudget.Bounded -> {
                val current = tightest
                if (current == null || parsed.bytes < current.bytes) {
                    tightest = parsed.copy(source = path)
                }
            }
            is MemoryBudget.Unbounded -> if (unbounded == null) unbounded = parsed.copy(source = path)
            else -> Unit
        }
    }
    return tightest ?: unbounded
}

private fun parseV2(text: String): MemoryBudget? {
    val value = text.trim()
    if (value == "max") return MemoryBudget.Unbounded("")
    val bytes = value.toLongOrNull() ?: return null
    return if (bytes > 0) MemoryBudget.Bounded(bytes, "") else null
}

private fun parseV1(text: String): MemoryBudget? {
    val bytes = text.trim().toLongOrNull() ?: return null
    return when {
        bytes >= V1_UNLIMITED -> MemoryBudget.Unbounded("")
        bytes > 0 -> MemoryBudget.Bounded(bytes, "")
        else -> null
    }
}

/**
 * `0::/kubepods/besteffort/pod…` — the v2 line is the one with an empty controller field, and there
 * is exactly one of it. A v1-only host has no such line and this returns null rather than the first
 * path it sees.
 */
internal fun cgroupV2Path(procSelfCgroup: String): String? =
    procSelfCgroup
        .lineSequence()
        .map { it.split(':') }
        .firstOrNull { it.size >= 3 && it[0] == "0" && it[1].isEmpty() }
        ?.let { it.drop(2).joinToString(":") }
        ?.ifEmpty { "/" }

/**
 * `12:memory:/docker/abc` — and the controller field can carry several names at once
 * (`4:cpu,cpuacct:/…`), so it is split rather than compared whole. A hierarchy that mounts `memory`
 * beside `cpu` is the usual v1 layout, not an exotic one.
 */
internal fun cgroupV1MemoryPath(procSelfCgroup: String): String? =
    procSelfCgroup
        .lineSequence()
        .map { it.split(':') }
        .firstOrNull { it.size >= 3 && it[1].split(',').contains("memory") }
        ?.let { it.drop(2).joinToString(":") }
        ?.ifEmpty { "/" }

/** `/a/b` becomes `/a/b`, `/a`, `/` — the leaf first, because the leaf is the usual answer. */
internal fun ancestors(path: String): List<String> {
    val segments = path.split('/').filter { it.isNotEmpty() }
    return (segments.indices.reversed().map { "/" + segments.take(it + 1).joinToString("/") } + "/")
}

/** `/sys/fs/cgroup//memory.max` is a real path on Linux, but it reads badly in an error message. */
private fun String.clean(): String = replace("//", "/")
