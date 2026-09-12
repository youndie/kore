package io.github.youndie.kore.version

/**
 * Which build this binary is.
 *
 * **Compiled in, not read at runtime.** Kotlin/Native has no JVM-style resource loading and no
 * manifest, so a resource-based implementation works on the JVM, compiles on native, and returns
 * nothing there — which is the shape of bug this repository is written to avoid. The Gradle plugin
 * generates a Kotlin source file that implements this, and the generated file is an **input of the
 * compilation** rather than a side effect of the build.
 *
 * The question it answers is asked during a deploy and during an incident: *is what is running the
 * thing we think we pushed?* Today that is three separate claims — the image tag matches the chart
 * value matches the commit — each true on its own, and the case somebody is investigating is
 * precisely the one where they disagree.
 */
public interface BuildIdentity {
    /** The project's version. */
    public val version: String

    /** The commit this was built from, or [UNKNOWN] when the build had no git to ask. */
    public val commit: String

    /**
     * Whether the working tree had uncommitted changes.
     *
     * The difference between "this is commit `abc1234`" and "this is something somebody had on their
     * laptop", which is worth one boolean.
     */
    public val dirty: Boolean

    /** When it was built, ISO-8601. See the quirk about reproducibility in the feature document. */
    public val builtAt: String

    /** `abc1234` or `abc1234-dirty`, or `unknown`. What `/version` and the agents report. */
    public val describe: String
        get() =
            when {
                commit == UNKNOWN -> UNKNOWN
                dirty -> "$commit-dirty"
                else -> commit
            }

    public companion object {
        /**
         * What a build with no git reports.
         *
         * A word that reads as an absence, deliberately — an invented-looking hash would be worse
         * than admitting the build could not tell. A library that cannot be built in a container
         * without `.git` cannot be built in half the CI systems there are, so this is not a failure.
         */
        public const val UNKNOWN: String = "unknown"
    }
}
