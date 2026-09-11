package io.github.youndie.kore.config

/**
 * What this target can say about the *set* of variables, as opposed to one by name.
 *
 * **A sealed answer rather than a nullable set, and that is the whole point of the type.** The
 * unknown-variable check needs to list the environment, and on one of kore's targets it cannot. If
 * "cannot list" were an empty set, a deployment on that target would read "no unknown variables
 * found" as evidence — a check that always passes is worse than an absent one. So the two are
 * different shapes and the compiler makes a caller handle both.
 */
public sealed interface EnvironmentNames {
    /** Every variable name this process was started with. */
    public class Listed(public val names: Set<String>) : EnvironmentNames

    /** This target cannot list them, and says why so `--print-config` can print it. */
    public class Unavailable(public val reason: String) : EnvironmentNames
}

/**
 * Reading the environment.
 *
 * An interface because there is no one way to do it: `System.getenv` is JVM-only and `getenv` is
 * POSIX (research §1.5). [names] is the half that is not universal at all.
 */
public interface Environment {
    public fun lookup(name: String): String?

    /** See [EnvironmentNames]. */
    public fun names(): EnvironmentNames

    public companion object {
        /** For tests and for reading a supplied map instead of the process's own environment. */
        public fun of(values: Map<String, String>): Environment =
            object : Environment {
                override fun lookup(name: String): String? = values[name]

                override fun names(): EnvironmentNames = EnvironmentNames.Listed(values.keys)
            }

        /** An environment that can be read by name and not listed — what macOS native actually is. */
        public fun unlistable(values: Map<String, String>, reason: String): Environment =
            object : Environment {
                override fun lookup(name: String): String? = values[name]

                override fun names(): EnvironmentNames = EnvironmentNames.Unavailable(reason)
            }
    }
}

/** The environment this process was started with. */
public expect fun systemEnvironment(): Environment
