package io.github.youndie.kore.sample

import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.printConfig
import io.github.youndie.kore.config.systemEnvironment

/**
 * Everything both entry points do, so the two `main`s stay the four lines that genuinely differ.
 *
 * The order here is a claim about what a service owes an operator, and it is the order kore's
 * configuration feature was designed around:
 *
 * 1. **`--print-config` answers before anything else**, including the build line. It is asked most
 *    often *because* the process will not start, so it must not depend on the process starting — and
 *    it exits with the same verdict the start would have given.
 * 2. **The configuration is read once, before anything serves.** A missing value is a process that
 *    does not start, not a route that fails later under a user.
 * 3. The refusal is printed as the message and nothing else. A stack trace here would bury the two
 *    lines that say which variable and why under frames nobody reading `kubectl logs` wants.
 */
public fun sampleMain(args: Array<String>) {
    if (args.any { it == "--print-config" }) {
        val printed = SampleConfig.SCHEMA.printConfig()
        print(printed.text)
        endProcess(printed.exitCode)
    }

    println(buildLine())
    val options = SampleOptions.parse(args)

    // THE CONTROL DOES NOT READ IT, and that is the comparison rather than an omission: it is a
    // service written the ordinary way, and the ordinary way is a constant and a command-line flag.
    if (!options.kore) {
        startSample(options)
        return
    }

    val settings =
        try {
            readSampleSettings()
        } catch (refusal: ConfigurationException) {
            println(refusal.message)
            endProcess(1)
        }
    println(settings.describe())
    startKoreSample(options, settings)
}

/** Reads the schema against the process's own environment. Throws [ConfigurationException] — see above. */
public fun readSampleSettings(): SampleSettings {
    val configuration = SampleConfig.SCHEMA.read(systemEnvironment())
    return SampleSettings(
        port = configuration[SampleConfig.PORT],
        workMillis = configuration[SampleConfig.WORK_MS].inWholeMilliseconds,
        poolDsn = configuration[SampleConfig.POOL_DSN],
        observed = configuration[SampleConfig.TRACY_ENDPOINT] != null,
    )
}

/**
 * Ends the process with [code].
 *
 * **An `expect`/`actual` for something that exists on both targets**, which looks redundant until the
 * metadata compilation says otherwise: `kotlin.system.exitProcess` is declared for the JVM and for
 * Kotlin/Native and **not** in common, so `commonMain` cannot see it. Per-target compilation is happy;
 * `compileCommonMainKotlinMetadata` is where it fails, which is one more reason `./gradlew build` is
 * the gate and a target-by-target compile is not.
 */
internal expect fun endProcess(code: Int): Nothing
