package io.github.youndie.kore

/**
 * Which target this binary was built for, and what kore can therefore do on it.
 *
 * This exists because one of kore's promises is not universal. Enumerating the environment — which
 * "fail on an unknown variable" needs — is available on the JVM and on the Linux native targets and
 * is **not** available on macOS native: `platform.posix` there exposes neither `environ` nor
 * `__environ`, and `_NSGetEnviron` is not in `platform.posix`, `platform.darwin` or
 * `platform.Foundation` either (`docs/research/research-architecture.md` §1.5).
 *
 * A capability that is absent has to be *declared* rather than discovered. The alternative is a
 * check that always passes on one target, which a deployment reads as evidence — and that is a
 * worse outcome than not having the check at all.
 */
public class KorePlatform internal constructor(
    /** `jvm`, `linux` or `macos`. Printed by `--print-config` and served by `/version`. */
    public val name: String,
) {
    /**
     * Whether the whole environment can be listed, not merely queried by name.
     *
     * **Derived rather than declared** (changed in B-22). Each target used to state this as a
     * constant beside the implementation that had to match it — two sources of truth for one fact,
     * and the day they disagree is the day a deployment is told it has a capability it does not.
     * Now there is one: the environment answers, and this reports what it answered.
     */
    public val canEnumerateEnvironment: Boolean
        get() = io.github.youndie.kore.config.systemEnvironment().names() is
            io.github.youndie.kore.config.EnvironmentNames.Listed

    override fun toString(): String =
        "KorePlatform($name, canEnumerateEnvironment=$canEnumerateEnvironment)"
}

/** The platform this binary was built for. */
public expect fun korePlatform(): KorePlatform
