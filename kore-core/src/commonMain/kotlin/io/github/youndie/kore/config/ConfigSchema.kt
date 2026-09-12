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
    /**
     * Masked when the field was declared secret — rule 7, a property of the declaration.
     *
     * **Absence is checked before secrecy**, and the order is the whole content of this property.
     * An absent secret carries nothing to leak, so saying it is absent discloses nothing; masking it
     * answers *"I am not showing you"* to a question about **existence**, which is the question
     * `--print-config` is asked when a [ConfigPair] is involved. It also made the two halves of one
     * decision render differently — the endpoint `null` and the key `••••••` — so a reader could not
     * tell "no agent here" from "an agent I am not printing the key of". Reported from the first
     * consumer as [youndie/kore#62](https://github.com/youndie/kore/issues/62).
     */
    public val rendered: String
        get() =
            when {
                value == null -> "null"
                secret -> "••••••"
                else -> value.toString()
            }

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
     * What a read produced, problems and all.
     *
     * Separate from [read] because `--print-config` is asked most often *because* the process will
     * not start: it has to show what it did resolve alongside what it could not, and a reader that
     * threw on the first problem could show neither.
     */
    public class Attempt internal constructor(
        public val values: List<ResolvedValue>,
        public val problems: List<ConfigProblem>,
    ) {
        public val usable: Boolean get() = problems.isEmpty()
    }

    /** Reads without throwing. [read] is this plus the refusal. */
    public fun attempt(environment: Environment): Attempt {
        val result = readInto(environment)
        return Attempt(result.first.values.toList(), result.second)
    }

    /**
     * Reads once, before anything serves.
     *
     * A missing value is a process that will not start rather than a route that fails later under a
     * user — rule 2, taken from the first consumer rather than invented.
     */
    public fun read(environment: Environment): Configuration {
        val (resolved, problems) = readInto(environment)
        if (problems.isNotEmpty()) throw ConfigurationException(prefix, problems)
        return Configuration(prefix, resolved)
    }

    private fun readInto(environment: Environment): Pair<LinkedHashMap<String, ResolvedValue>, List<ConfigProblem>> {
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


        // THE UNKNOWN-VARIABLE REFUSAL, and it is scoped to the prefix for a reason that decides
        // whether the feature is usable at all: a container's environment carries PATH, HOSTNAME,
        // KUBERNETES_SERVICE_HOST and every *_PORT the kubelet injects. A check over the whole
        // environment fails on its first deployment, gets switched off, and is never switched on
        // again — so an unscoped version of this feature is a feature that does not exist.
        //
        // A target that cannot list the environment contributes nothing here, and does NOT quietly
        // report "none": that distinction lives in EnvironmentNames, and `--print-config` prints
        // which of the two happened.
        val listing = environment.names()
        if (listing is EnvironmentNames.Listed) {
            val declared = keys.map { variableOf(it) }.toSet()
            listing.names
                .filter { it.startsWith("${prefix}_") && it !in declared }
                .sorted()
                .forEach { unknown ->
                    // Naming the DECLARED name it is probably a misspelling of, because that is the
                    // whole failure: `SAMPLE_TIMEOUT_MSEC` beside a declared `SAMPLE_TIMEOUT_MS` is a
                    // setting somebody wrote and the process is ignoring, and a message that names
                    // only the unknown half leaves the reader to find the other one.
                    val nearest = declared.minByOrNull { distance(unknown, it) }
                    val suggestion =
                        nearest
                            ?.takeIf { distance(unknown, it) <= MAX_SUGGESTION_DISTANCE }
                            ?.let { " — did you mean $it?" }
                            ?: ""
                    problems +=
                        ConfigProblem(
                            unknown,
                            "is set under the ${prefix}_ prefix and is not declared$suggestion",
                        )
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

        return resolved to problems.toList()
    }

    private companion object {
        /**
         * How different a declared name may be and still be offered as the thing that was meant.
         *
         * Four edits is about a plural, a suffix, or a transposition — `_MSEC` for `_MS`, `WORKER`
         * for `WORKERS`. Beyond that a suggestion stops being a help and starts being a guess that
         * sends the reader to the wrong variable, which is worse than no suggestion.
         */
        const val MAX_SUGGESTION_DISTANCE = 4

        /** Levenshtein. Small enough to write, and the alternative is a dependency for one message. */
        fun distance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}
