package io.github.youndie.kore.oracle

import kotlin.system.exitProcess

/**
 * **Does the sample actually refuse, in the image, the way the document says?** — B-50.
 *
 * `kore-core`'s suites assert the schema's behaviour: a required key missing is a problem, an
 * undeclared variable under the prefix is a problem, a secret renders masked. What they cannot
 * assert is that a *service* wired against it refuses to start — that the refusal reaches PID 1 as a
 * non-zero exit rather than being caught, logged and served anyway. That is the difference between a
 * library that behaves and a sample that demonstrates, and it is the whole reason B-50 exists: the
 * feature document claimed a sample that did not exist for weeks, and no unit test could notice.
 *
 *     ./gradlew :samples:oracle:configRefusal
 *
 * Four cases against the real image, each with the exit code and the text that must appear:
 *
 * 1. configured — starts, so the other three are refusals and not a binary that never worked;
 * 2. the required key missing — refuses, naming the variable;
 * 3. a **near miss** under the prefix — refuses, naming the declared variable it is probably a
 *    misspelling of. Ignoring it silently is the failure the check exists for;
 * 4. half the pair set — refuses, because one without the other is a deployment that believes it is
 *    observed and is not.
 *
 * Plus `--print-config`, in both verdicts, because a flag asked *because* the process will not start
 * has to work when it will not.
 */
private const val DSN = "postgres://oracle/sample"

private class Case(
    val name: String,
    val env: Map<String, String>,
    val args: List<String> = listOf("--kore=true"),
    val wantExit: Int,
    val wantText: List<String>,
    /** Text that must NOT be there. A masked line for a secret nobody set is the case this exists for. */
    val wantAbsent: List<String> = emptyList(),
)

fun main() {
    val image = "kore-sample:native"
    val cases =
        listOf(
            Case(
                "configured — the positive control",
                mapOf("SAMPLE_POOL_DSN" to DSN),
                wantExit = -1, // stays up; see below
                wantText = listOf("configured: port=8080", "pool=$DSN"),
            ),
            Case(
                "the required key is missing",
                emptyMap(),
                wantExit = 1,
                wantText = listOf("SAMPLE_POOL_DSN", "is required and is not set"),
            ),
            Case(
                "a near miss under the prefix",
                mapOf("SAMPLE_POOL_DSN" to DSN, "SAMPLE_WORK_MSEC" to "3000"),
                wantExit = 1,
                wantText = listOf("SAMPLE_WORK_MSEC", "did you mean SAMPLE_WORK_MS?"),
            ),
            Case(
                "half of the observability pair",
                mapOf("SAMPLE_POOL_DSN" to DSN, "SAMPLE_TRACY_ENDPOINT" to "https://tracy.example"),
                wantExit = 1,
                wantText = listOf("SAMPLE_TRACY_KEY", "must be set together with SAMPLE_TRACY_ENDPOINT"),
            ),
            Case(
                "--print-config on a usable configuration",
                mapOf("SAMPLE_POOL_DSN" to DSN, "SAMPLE_TRACY_ENDPOINT" to "https://tracy.example", "SAMPLE_TRACY_KEY" to "s3cret"),
                args = listOf("--print-config"),
                wantExit = 0,
                // The secret MASKED, and this line is the one that would matter on the day it broke.
                wantText = listOf("SAMPLE_TRACY_KEY", "••••••", "unknown SAMPLE_ variables: none"),
            ),
            Case(
                // kore#62. The pair's two halves must render the same when neither is set, or the
                // print answers "I am not showing you" to a question about existence.
                "--print-config tells an absent secret from a set one",
                mapOf("SAMPLE_POOL_DSN" to DSN),
                args = listOf("--print-config"),
                wantExit = 0,
                wantText = listOf("SAMPLE_TRACY_ENDPOINT", "SAMPLE_TRACY_KEY"),
                wantAbsent = listOf("••••••"),
            ),
            Case(
                "--print-config on a configuration that will not start",
                emptyMap(),
                args = listOf("--print-config"),
                wantExit = 1,
                wantText = listOf("this configuration will not start", "SAMPLE_POOL_DSN"),
            ),
        )

    var failures = 0
    for (case in cases) {
        val outcome = runCase(image, case)
        val exitOk = if (case.wantExit == -1) outcome.running else outcome.exit == case.wantExit
        val missing = case.wantText.filterNot { outcome.output.contains(it) }
        val present = case.wantAbsent.filter { outcome.output.contains(it) }
        val ok = exitOk && missing.isEmpty() && present.isEmpty()
        if (!ok) failures++
        println("${if (ok) "ok  " else "FAIL"}  ${case.name}")
        if (!ok) {
            println("      wanted exit ${if (case.wantExit == -1) "(still running)" else case.wantExit}, got ${if (outcome.running) "(still running)" else outcome.exit.toString()}")
            missing.forEach { println("      missing from the output: $it") }
            present.forEach { println("      should not be in the output: $it") }
            println(outcome.output.lines().joinToString("\n") { "      | $it" })
        }
    }

    println()
    println(if (failures == 0) "SUPPORTED: the sample refuses in the image, and says which variable and why." else "$failures of ${cases.size} cases failed.")
    exitProcess(if (failures == 0) 0 else 1)
}

private class Outcome(val exit: Int, val running: Boolean, val output: String)

private fun runCase(image: String, case: Case): Outcome {
    val container = Container(image, case.args, case.env)
    return try {
        container.start()
        // A refusal is immediate; a start keeps running. Waiting a fixed moment and then asking is
        // enough to tell the two apart, and it is what makes the positive control a control: a
        // binary that refused everything would fail case 1 rather than passing the other five.
        Thread.sleep(1_500)
        Outcome(container.exitCode(), container.isRunning(), container.logs())
    } finally {
        container.remove()
    }
}
