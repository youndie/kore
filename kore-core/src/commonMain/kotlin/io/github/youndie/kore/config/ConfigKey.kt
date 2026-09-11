package io.github.youndie.kore.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Where a value came from. Rule 8: "the default was used" and "the environment agreed with the default" are different facts, and the difference is what a renamed variable looks like. */
public enum class Origin {
    DEFAULT,
    ENV,
}

/**
 * One declared variable: its name, its type, whether it is required, and whether it is a secret.
 *
 * **No reflection anywhere.** A key is a value the caller holds and indexes the configuration with,
 * which is what makes this work identically on Kotlin/Native — and what makes a typo in a field name
 * a compile error rather than a runtime lookup that returns null.
 */
public class ConfigKey<T> internal constructor(
    /** Without the prefix. The schema owns the prefix; a key that carried one could disagree with it. */
    public val name: String,
    public val default: T?,
    public val required: Boolean,
    /**
     * Masked wherever the configuration is rendered.
     *
     * A property of the **declaration**, not a list of names somebody keeps in sync with the schema
     * — rule 7. A list is a second schema, and the day it disagrees with the first is the day a
     * secret is printed.
     */
    public val secret: Boolean,
    internal val parse: (String) -> T,
    internal val typeName: String,
) {
    public companion object {
        /** A required string. Absent, the process does not start. */
        public fun required(name: String, secret: Boolean = false): ConfigKey<String> =
            ConfigKey(name, null, true, secret, { it }, "string")

        /** A string with a default. */
        public fun string(name: String, default: String): ConfigKey<String> =
            ConfigKey(name, default, false, false, { it }, "string")

        /**
         * Absent is a legitimate answer, and `null` is what it means.
         *
         * This is the shape a [ConfigPair] is made of: an agent's endpoint and its key are each
         * optional — both unset means "no agent", which is a decision — and setting exactly one is
         * the refusal rule 5 exists for. Without an optional key there is nothing for a pair to be
         * made of, which is how this turned up.
         */
        public fun optional(name: String, secret: Boolean = false): ConfigKey<String?> =
            ConfigKey(name, null, false, secret, { it }, "string")

        /** A secret: required, and masked wherever it is rendered. */
        public fun secret(name: String): ConfigKey<String> = required(name, secret = true)

        public fun int(name: String, default: Int? = null): ConfigKey<Int> =
            ConfigKey(name, default, default == null, false, { it.toIntOrNull() ?: fail(name, it, "an integer") }, "int")

        public fun long(name: String, default: Long? = null): ConfigKey<Long> =
            ConfigKey(name, default, default == null, false, { it.toLongOrNull() ?: fail(name, it, "a long") }, "long")

        /**
         * A switch. Rule 6: **the exact string `true`**, and anything else is false.
         *
         * `"True"`, `"1"` and `"yes"` are all false, and an unset switch is false. A security switch
         * that opens on a misspelling is the switch that ships open — so the default is the closed
         * position and there is no way to declare it otherwise.
         */
        public fun boolean(name: String): ConfigKey<Boolean> =
            ConfigKey(name, false, false, false, { it == "true" }, "boolean (the exact string \"true\")")

        /** Milliseconds, because that is what a chart writes. */
        public fun millis(name: String, default: Duration? = null): ConfigKey<Duration> =
            ConfigKey(
                name,
                default,
                default == null,
                false,
                { (it.toLongOrNull() ?: fail(name, it, "a number of milliseconds")).milliseconds },
                "duration in milliseconds",
            )

        private fun fail(name: String, value: String, expected: String): Nothing =
            throw ConfigValueException(name, value, expected)
    }
}

/** A value that did not parse. Carries the variable, what it held, and what was wanted. */
public class ConfigValueException(
    public val variable: String,
    public val value: String,
    public val expected: String,
) : IllegalArgumentException("$variable is not $expected: \"$value\"")

/**
 * Two variables that must be set together or not at all.
 *
 * Rule 5, and it is taken from the one place in the portfolio that already got it right rather than
 * invented: an endpoint without its key is a **refusal at startup**, because "one without the other
 * is a deployment that believes it is observed and is not" (research §1.11). Three services had this
 * written out by hand; here it is a declaration.
 */
public class ConfigPair(
    public val first: String,
    public val second: String,
)
