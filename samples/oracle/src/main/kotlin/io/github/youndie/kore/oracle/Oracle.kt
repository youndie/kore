package io.github.youndie.kore.oracle

import kotlin.system.exitProcess

/**
 * `kill -TERM` under load, against one image.
 *
 * A7 — "the two platforms agree" — is not evaluated here: it compares two runs, so it belongs to
 * whatever drives both. Run this twice and diff the findings.
 */
fun main(args: Array<String>) {
    val options = Options.parse(args)

    println(
        "oracle: ${options.describeSubject()} path=${options.path} work=${options.workMillis}ms " +
            "connections=${options.connections} grace=${options.graceMillis}ms " +
            "subject-args=${options.subjectArgs} env=${options.subjectEnv.keys}",
    )

    val observations =
        OracleRun(
            container = options.subject(),
            workMillis = options.workMillis,
            path = options.path,
            connections = options.connections,
            readTimeoutMillis = options.readTimeoutMillis,
            graceMillis = options.graceMillis,
        ).execute()

    val findings = evaluate(observations, options.preDrainWaitMillis, options.graceMillis)

    println()
    println("PID 1: ${observations.pid1}")
    println("exchanges: ${observations.exchanges.size}, spanning the signal: ${observations.spanningSignal.size}, after it: ${observations.afterSignal.size}")
    println("readiness samples: ${observations.readiness.size}${if (observations.readinessAbsent) " (all 404 - no readiness endpoint)" else ""}")
    println()
    findings.forEach { println("  ${it.verdict.name.padEnd(15)} ${it.id} — ${it.detail}") }
    println()

    val failed = findings.count { it.verdict == Verdict.FAIL }
    val inconclusive = findings.count { it.verdict == Verdict.INCONCLUSIVE }
    val notApplicable = findings.count { it.verdict == Verdict.NOT_APPLICABLE }

    println(
        "result: ${findings.count { it.verdict == Verdict.PASS }} passed, $failed failed, " +
            "$inconclusive inconclusive, $notApplicable not applicable",
    )

    // An inconclusive run is not a pass. It exits non-zero for the same reason a failure does: the
    // question was not answered, and a caller that treats "not answered" as "fine" is the caller
    // this distinction exists for.
    exitProcess(if (failed > 0 || inconclusive > 0) 1 else 0)
}

class Options(
    /**
     * The image to run, or empty when [command] names a local process instead.
     *
     * Exactly one of the two, and the run says which it took. See [Subject] for why there are two
     * ([#85](https://github.com/youndie/kore/issues/85)).
     */
    val image: String,
    /**
     * The executable and its arguments, **comma-separated** — `--command=bin/keel,--flag`.
     *
     * Commas for the same reason `--subject-args` uses them, and the first draft of this flag was
     * written with spaces and met it within the hour: Gradle's `--args` splits on spaces, so
     * `--command=java -jar app.jar` reaches the oracle as three arguments and the subject is `java`
     * with nothing to run. It failed as `the container never answered /health`, which names neither
     * the splitting nor this flag.
     */
    val command: List<String>,
    /** Only for [command]: a container publishes a port the harness can read back, a process does not. */
    val port: Int,
    val workMillis: Long,
    /**
     * The route the load drives, and the only part of the subject the oracle has to be told about.
     *
     * Hardcoded to `samples/service`'s `/work?ms=` until [#81](https://github.com/youndie/kore/issues/81),
     * which is to say the assertions could only ever be made about kore's own sample — and a consumer
     * adopting kore wants exactly these assertions about **its** binary, because the interesting
     * failures are in the wiring: a pool closed in `ApplicationStopping`, a `runUntilSignal` installed
     * before the server is serving, a participant that ignores cancellation.
     *
     * `{work}` is substituted with [workMillis], so the default reproduces the old behaviour exactly
     * and a consumer's own route needs no placeholder at all:
     *
     * ```
     * --path=/items
     * --path=/work?ms={work}      # the default
     * ```
     *
     * **The probe paths stay fixed on purpose.** `/health/ready` and `/health` are what
     * `installKoreProbes` mounts — they are kore's contract rather than the subject's choice, and a
     * consumer that moved them has a different problem than this flag solves.
     */
    val path: String,
    val connections: Int,
    val graceMillis: Long,
    val readTimeoutMillis: Int,
    val preDrainWaitMillis: Long?,
    /** Passed to the container's entry point, so one image can be run in several configurations. */
    val subjectArgs: List<String>,
    /**
     * The subject's environment, `NAME=value` separated by commas.
     *
     * **The oracle was unable to start its own sample for four days without this.** B-50 gave
     * `samples/service` a configuration schema with a required key, so the kore arm refuses to start
     * unconfigured — which is the feature. `Container` grew environment support in the same change
     * and `measure` was taught to pass it; this harness was not, and nothing said so, because the
     * oracle is invoked by name and is in neither `build` nor `check`. The failure was
     * `the container never answered /health`, which reads as a subject that would not start — and it
     * was, for a reason the harness could have supplied.
     *
     * A flag rather than kore's own variables written in here: an oracle that knew which variables
     * `samples/service` needs would be the same defect [#81](https://github.com/youndie/kore/issues/81)
     * reports about the route.
     */
    val subjectEnv: Map<String, String>,
) {
    /**
     * The subject this run drives.
     *
     * The refusal is here rather than in `parse` because "exactly one of two" is a property of the
     * pair, and a message naming both is what a caller who gave neither needs.
     */
    fun subject(): Subject =
        when {
            image.isNotBlank() && command.isNotEmpty() ->
                error("--image and --command are two subjects; give one")
            image.isNotBlank() -> Container(image, subjectArgs, subjectEnv)
            command.isNotEmpty() -> LocalProcess(command + subjectArgs, port, subjectEnv)
            else -> error("one of --image=<tag> or --command=\"<executable> <args>\" is required")
        }

    fun describeSubject(): String =
        if (image.isNotBlank()) "image=$image" else "command=${command.joinToString(" ")} port=$port"

    companion object {
        fun parse(args: Array<String>): Options {
            val map = HashMap<String, String>()
            args.forEach { argument ->
                val clean = argument.removePrefix("--")
                val equals = clean.indexOf('=')
                if (equals > 0) map[clean.take(equals)] = clean.drop(equals + 1)
            }
            return Options(
                image = map["image"].orEmpty(),
                command = map["command"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
                port = map["port"]?.toInt() ?: 8080,
                workMillis = map["work"]?.toLong() ?: 3_000,
                path = map["path"] ?: "/work?ms={work}",
                connections = map["connections"]?.toInt() ?: 8,
                graceMillis = map["grace"]?.toLong() ?: 30_000,
                readTimeoutMillis = map["read-timeout"]?.toInt() ?: 60_000,
                preDrainWaitMillis = map["pre-drain"]?.toLong(),
                // COMMA-separated, not space-separated. Gradle's `--args` splits on spaces, so a
                // space here means the second subject argument is parsed as one of the ORACLE's —
                // silently, and the run then measures a subject that was never configured. That
                // happened once and produced a cell of four green results about nothing.
                subjectArgs = map["subject-args"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
                // Comma-separated for the same reason as `--subject-args` above, and split on the
                // FIRST `=` only, so a value may contain one — a DSN usually does.
                subjectEnv =
                    map["env"]
                        ?.split(',')
                        ?.filter { it.isNotBlank() }
                        ?.associate { entry ->
                            val equals = entry.indexOf('=')
                            require(equals > 0) { "--env entries are NAME=value, got \"$entry\"" }
                            entry.take(equals) to entry.drop(equals + 1)
                        }
                        ?: emptyMap(),
            )
        }
    }
}
