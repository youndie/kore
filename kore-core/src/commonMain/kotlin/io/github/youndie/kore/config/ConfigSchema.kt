package io.github.youndie.kore.config

/** One thing wrong with the configuration, named so a person can fix it without reading the source. */
public class ConfigProblem(
    public val variable: String,
    public val message: String,
) {
    override fun toString(): String = "$variable: $message"
}

/**
 * Everything that is wrong, not the first thing.
 *
 * A process that fails on the first missing variable makes you fix them one at a time, one restart
 * each — and a deployment being configured for the first time has several. Collecting them is the
 * difference between one round trip and five.
 */
public class ConfigurationException(
    public val prefix: String,
    public val problems: List<ConfigProblem>,
) : IllegalStateException(
        "the $prefix configuration is not usable:\n" + problems.joinToString("\n") { "  - $it" },
    )

/** A resolved value, with where it came from. */
public class ResolvedValue(
    public val variable: String,
    public val origin: Origin,
    public val secret: Boolean,
    internal val value: Any?,
) {
    /** Masked when the field was declared secret — rule 7, a property of the declaration. */
    public val rendered: String get() = if (secret) "••••••" else value.toString()

    override fun toString(): String = "$variable = $rendered ($origin)"
}

/** What the process is configured as, read once. */
public class Configuration internal constructor(
    public val prefix: String,
    private val resolved: Map<String, ResolvedValue>,
) {
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(key: ConfigKey<T>): T =
        (resolved[key.name] ?: error("${key.name} is not part of the $prefix schema")).value as T

    /** Every value with its origin, in declaration order. What B-23 prints. */
    public fun values(): List<ResolvedValue> = resolved.values.toList()
}

/**
 * A declared set of environment variables, under one prefix.
 *
 * **One source: the environment.** No files, no remote configuration, no precedence — every
 * additional source is a rule about which of two values wins, which is a new way to be wrong about
 * what is running. The value here is the schema and the refusal, not the plumbing.
 *
 * **The prefix is part of the schema** and not decoration. It is what makes the unknown-variable
 * check of B-24 possible at all: a container's environment carries `PATH`, `HOSTNAME`,
 * `KUBERNETES_SERVICE_HOST` and every `*_PORT` the kubelet injects, so a check over the whole
 * environment would fail on its first deployment, be switched off, and never be switched on again.
 */
public class ConfigSchema(
    public val prefix: String,
    public val keys: List<ConfigKey<*>>,
    public val pairs: List<ConfigPair> = emptyList(),
) {
    init {
        require(prefix.isNotBlank()) { "a schema needs a prefix; the unknown-variable check has nothing to scope to without one" }
        val duplicates = keys.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "declared twice: ${duplicates.joinToString()}" }
        val declared = keys.map { it.name }.toSet()
        pairs.forEach { pair ->
            require(pair.first in declared && pair.second in declared) {
                "a pair names a variable the schema does not declare: ${pair.first} / ${pair.second}"
            }
        }
    }

    /** The full variable name as it appears in the environment. */
    public fun variableOf(key: ConfigKey<*>): String = "${prefix}_${key.name}"

    /**
     * Reads once, before anything serves.
     *
     * A missing value is a process that will not start rather than a route that fails later under a
     * user — rule 2, taken from the first consumer rather than invented.
     */
    public fun read(environment: Environment): Configuration {
        val problems = mutableListOf<ConfigProblem>()
        val resolved = LinkedHashMap<String, ResolvedValue>()

        keys.forEach { key ->
            val variable = variableOf(key)
            when (val raw = environment.lookup(variable)?.takeIf { it.isNotBlank() }) {
                null ->
                    if (key.required) {
                        problems += ConfigProblem(variable, "is required and is not set")
                    } else {
                        resolved[key.name] = ResolvedValue(variable, Origin.DEFAULT, key.secret, key.default)
                    }

                else ->
                    try {
                        resolved[key.name] = ResolvedValue(variable, Origin.ENV, key.secret, key.parse(raw))
                    } catch (failure: ConfigValueException) {
                        problems += ConfigProblem(variable, "is not ${key.typeName}: \"${failure.value}\"")
                    }
            }
        }

        pairs.forEach { pair ->
            val first = resolved[pair.first]
            val second = resolved[pair.second]
            val firstSet = first?.origin == Origin.ENV
            val secondSet = second?.origin == Origin.ENV
            if (firstSet != secondSet) {
                val missing = if (firstSet) pair.second else pair.first
                val present = if (firstSet) pair.first else pair.second
                problems += ConfigProblem(
                    "${prefix}_$missing",
                    "must be set together with ${prefix}_$present — one without the other is a " +
                        "deployment that believes it is configured and is not",
                )
            }
        }

        if (problems.isNotEmpty()) throw ConfigurationException(prefix, problems.toList())
        return Configuration(prefix, resolved)
    }
}
