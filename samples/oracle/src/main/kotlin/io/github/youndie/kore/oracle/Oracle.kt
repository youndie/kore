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
        "oracle: image=${options.image} work=${options.workMillis}ms connections=${options.connections} " +
            "grace=${options.graceMillis}ms subject-args=${options.subjectArgs}",
    )

    val observations =
        OracleRun(
            container = Container(options.image, options.subjectArgs),
            workMillis = options.workMillis,
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
    val image: String,
    val workMillis: Long,
    val connections: Int,
    val graceMillis: Long,
    val readTimeoutMillis: Int,
    val preDrainWaitMillis: Long?,
    /** Passed to the container's entry point, so one image can be run in several configurations. */
    val subjectArgs: List<String>,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            val map = HashMap<String, String>()
            args.forEach { argument ->
                val clean = argument.removePrefix("--")
                val equals = clean.indexOf('=')
                if (equals > 0) map[clean.take(equals)] = clean.drop(equals + 1)
            }
            return Options(
                image = map["image"] ?: error("--image=<tag> is required"),
                workMillis = map["work"]?.toLong() ?: 3_000,
                connections = map["connections"]?.toInt() ?: 8,
                graceMillis = map["grace"]?.toLong() ?: 30_000,
                readTimeoutMillis = map["read-timeout"]?.toInt() ?: 60_000,
                preDrainWaitMillis = map["pre-drain"]?.toLong(),
                // COMMA-separated, not space-separated. Gradle's `--args` splits on spaces, so a
                // space here means the second subject argument is parsed as one of the ORACLE's —
                // silently, and the run then measures a subject that was never configured. That
                // happened once and produced a cell of four green results about nothing.
                subjectArgs = map["subject-args"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
            )
        }
    }
}
