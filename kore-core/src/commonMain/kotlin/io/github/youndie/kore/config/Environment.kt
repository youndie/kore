package io.github.youndie.kore.config

/**
 * Reading the environment, by name.
 *
 * An interface rather than a direct call because there is no one way to do it: `System.getenv` is
 * JVM-only and `getenv` is POSIX (research §1.5). The per-target implementations, and the harder
 * question of whether the environment can be **listed** at all, are B-22 — this is only the lookup.
 */
public fun interface Environment {
    public fun lookup(name: String): String?

    public companion object {
        /** For tests and for `--print-config` against a supplied map. */
        public fun of(values: Map<String, String>): Environment = Environment { values[it] }
    }
}
