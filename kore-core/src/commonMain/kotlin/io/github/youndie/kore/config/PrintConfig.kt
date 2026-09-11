package io.github.youndie.kore.config

import io.github.youndie.kore.korePlatform

/** What `--print-config` produced: the text to print and the code to exit with. */
public class PrintedConfig(
    public val text: String,
    /** Non-zero when the configuration would not start. The flag answers the same question the start does. */
    public val exitCode: Int,
)

/**
 * Renders the resolved configuration.
 *
 * **A flag rather than a route**, and the reason is in the question it answers: *what does this
 * deployment think it is configured as* is asked most often **because** the process will not start.
 * A route needs a process that started; a flag works in CI, in a `kubectl run` against the image, in
 * the one-shot migration container the portfolio already runs, and on a laptop.
 *
 * So it prints **what it did resolve even when the configuration is unusable**, and exits non-zero
 * with the same message the start would have given. Printing nothing on failure would make the flag
 * useless in exactly the case it exists for.
 *
 * @param extraSections appended verbatim. `kore-ktor` supplies the probe block this way, which is how
 *   `kore-core` prints it without knowing what an HTTP route is.
 */
public fun ConfigSchema.printConfig(
    environment: Environment = systemEnvironment(),
    extraSections: List<String> = emptyList(),
): PrintedConfig {
    val attempt = attempt(environment)
    val out = StringBuilder()

    out.appendLine("kore configuration — prefix ${prefix}_, target ${korePlatform().name}")
    out.appendLine()

    val width = attempt.values.maxOfOrNull { it.variable.length } ?: 0
    attempt.values.forEach { value ->
        // The ORIGIN is beside every line, not only the ones that differ. "The default was used" and
        // "the environment said the same thing as the default" are different facts, and the
        // difference is what a renamed variable looks like — rule 8.
        out.appendLine("  ${value.variable.padEnd(width)}  ${value.rendered.padEnd(24)}  ${value.origin.name.lowercase()}")
    }

    out.appendLine()
    out.append(unknownVariableSection(environment))

    extraSections.forEach { section ->
        out.appendLine()
        out.append(section)
    }

    if (!attempt.usable) {
        out.appendLine()
        out.appendLine("this configuration will not start:")
        attempt.problems.forEach { out.appendLine("  - $it") }
    }

    return PrintedConfig(out.toString(), if (attempt.usable) 0 else 1)
}

/**
 * Says whether the unknown-variable check can run **here**.
 *
 * It is printed always, not only when unavailable. A deployment that reads "no unknown variables
 * found" has to be able to tell that from "nothing was looked at", and the only way to be sure is
 * for the line to be there in both cases.
 */
private fun ConfigSchema.unknownVariableSection(environment: Environment): String =
    when (val names = environment.names()) {
        is EnvironmentNames.Listed -> {
            val declared = keys.map { variableOf(it) }.toSet()
            val unknown = names.names.filter { it.startsWith("${prefix}_") && it !in declared }.sorted()
            if (unknown.isEmpty()) {
                "unknown ${prefix}_ variables: none — the environment was listed and checked\n"
            } else {
                "unknown ${prefix}_ variables:\n" + unknown.joinToString("") { "  - $it\n" }
            }
        }

        is EnvironmentNames.Unavailable ->
            "unknown ${prefix}_ variables: NOT CHECKED — ${names.reason}\n"
    }
