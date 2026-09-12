package io.github.youndie.kore.oracle

/**
 * Takes the three numbers, alternating the arms, and prints the raw runs and a summary.
 *
 *   ./gradlew :samples:oracle:measure --args="--repeats=5 --work=300 --connections=8"
 *
 * Alternating rather than all-of-one-then-all-of-the-other, because the machine drifts: a background
 * job, a thermal step or another container arriving lands on whichever arm is running at the time,
 * and block ordering hands all of it to one of them.
 */
fun main(args: Array<String>) {
    val options = args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "") }
    val repeats = options["repeats"]?.toIntOrNull() ?: 5
    val work = options["work"]?.toLongOrNull() ?: 3_000
    val connections = options["connections"]?.toIntOrNull() ?: 8
    val grace = options["grace"]?.toLongOrNull() ?: 20_000
    val images = (options["images"] ?: "kore-sample:jvm,kore-sample:native").split(",")

    println("# the three numbers — research-oracle §4")
    println("repeats=$repeats (plus one discarded warm-up per cell) work=${work}ms connections=$connections grace=${grace}ms")
    println("images=${images.joinToString(" ")}")
    println()

    val taken = mutableMapOf<Pair<String, String>, MutableList<Measurement>>()

    for (image in images) {
        // One warm-up per cell, discarded, and SAID so rather than silently dropped. The first run
        // after a restart measures the page cache and the image layers, not the subject.
        for (arm in ARMS) {
            print("warm-up $image/${arm.name}: ")
            val warm = runCatching { run(image, arm, work, connections, grace) }
            println(warm.map { "discarded (first=${it.firstHealthMillis}ms stop=${it.stopMillis}ms)" }
                .getOrElse { "discarded (failed: ${it.message})" })
        }
        println()

        repeat(repeats) { round ->
            for (arm in ARMS) {
                val measurement = run(image, arm, work, connections, grace)
                taken.getOrPut(image to arm.name) { mutableListOf() } += measurement
                println(
                    "round ${round + 1} $image/${arm.name}: " +
                        "first /health ${measurement.firstHealthMillis}ms" +
                        (measurement.firstStartupMillis?.let { " first /health/startup ${it}ms" } ?: "") +
                        " rss ${measurement.rssKbAtReady ?: "?"}kB" +
                        " stop ${measurement.stopMillis ?: "no exit"}ms" +
                        " inFlight=${measurement.inFlightAtSignal} dropped=${measurement.droppedAtSignal} finished=${measurement.finishedAtSignal} exit=${measurement.exitCode}",
                )
            }
        }
        println()
    }

    println("## summary — median (min–max), n=$repeats per cell")
    println()
    println("| image | number | control | kore | difference |")
    println("|---|---|---|---|---|")
    for (image in images) {
        val control = taken[image to "control"].orEmpty()
        val kore = taken[image to "kore"].orEmpty()
        if (control.isEmpty() || kore.isEmpty()) continue
        row(image, "time to first /health", control.map { it.firstHealthMillis }, kore.map { it.firstHealthMillis }, "ms")
        row(image, "RSS at ready", control.mapNotNull { it.rssKbAtReady }, kore.mapNotNull { it.rssKbAtReady }, "kB")
        // THE VACUITY GUARD, per arm. A stop timed with nothing outstanding is not "stop under
        // load" — it is the process exiting with no work to finish, which is a different and much
        // smaller number. Two native control runs came in at ~478 ms that way and pulled the median
        // below the truth. Excluded, and the exclusion is reported rather than silent.
        val controlLoaded = control.filter { it.inFlightAtSignal > 0 }
        val koreLoaded = kore.filter { it.inFlightAtSignal > 0 }
        row(image, "stop under load", controlLoaded.mapNotNull { it.stopMillis }, koreLoaded.mapNotNull { it.stopMillis }, "ms")
        val dropped = (control.size - controlLoaded.size) + (kore.size - koreLoaded.size)
        if (dropped > 0) {
            println("| $image | — | — | — | $dropped run(s) excluded from stop: nothing was in flight at the signal |")
        }
    }
    println()
    println("## what happened to the work that was in flight at the signal")
    println()
    println("| image | arm | spanning the signal | finished | dropped |")
    println("|---|---|---|---|---|")
    for (image in images) {
        for (arm in ARMS.map { it.name }) {
            val runs = taken[image to arm].orEmpty().filter { it.inFlightAtSignal > 0 }
            if (runs.isEmpty()) continue
            val finished = runs.sumOf { it.finishedAtSignal }
            val dropped = runs.sumOf { it.droppedAtSignal }
            println("| $image | $arm | ${finished + dropped} | $finished | **$dropped** |")
        }
    }
    println()
    val startups = taken.filterKeys { it.second == "kore" }
    for ((key, runs) in startups) {
        val values = runs.mapNotNull { it.firstStartupMillis }
        if (values.isNotEmpty()) {
            println("${key.first}: kore's own `/health/startup` first answered 200 at ${summarise(values, "ms")} — " +
                "the control has no such route, so this one has no comparison and is reported alone.")
        }
    }
}

/**
 * The two arms, each with what it needs to start.
 *
 * The kore arm carries an environment because it reads a declared schema whose `POOL_DSN` is
 * required (B-50); the control carries none because a service written without kore has nowhere to
 * read one from. **That asymmetry is the subject of the comparison, not a flaw in it** — the
 * question is what a service costs wired each way, and reading a typed configuration is part of one
 * of the two ways.
 */
private class Arm(val name: String, val args: List<String>, val env: Map<String, String> = emptyMap())

private val ARMS =
    listOf(
        Arm("control", emptyList()),
        Arm("kore", listOf("--kore=true"), mapOf("SAMPLE_POOL_DSN" to "postgres://oracle/sample")),
    )

private fun run(image: String, arm: Arm, work: Long, connections: Int, grace: Long) =
    MeasureRun(
        image = image,
        arm = arm.name,
        koreArm = arm.name == "kore",
        subjectArgs = arm.args,
        subjectEnv = arm.env,
        workMillis = work,
        connections = connections,
        readTimeoutMillis = 30_000,
        graceMillis = grace,
    ).execute()

private fun row(image: String, name: String, control: List<Long>, kore: List<Long>, unit: String) {
    if (control.isEmpty() || kore.isEmpty()) {
        println("| $image | $name | — | — | not taken |")
        return
    }
    val difference = median(kore) - median(control)
    // Both halves, always: a percentage on a 3 ms start is not a finding, and an absolute with no
    // ratio hides how big it is relative to the thing it is added to.
    val ratio = if (median(control) == 0L) "—" else "%+.0f%%".format(difference * 100.0 / median(control))
    println("| $image | $name | ${summarise(control, unit)} | ${summarise(kore, unit)} | ${"%+d".format(difference)} $unit ($ratio) |")
}

private fun summarise(values: List<Long>, unit: String): String =
    "${median(values)} $unit (${values.min()}–${values.max()})"

private fun median(values: List<Long>): Long {
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
}
