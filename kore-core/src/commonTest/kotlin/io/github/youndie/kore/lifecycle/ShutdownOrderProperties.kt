package io.github.youndie.kore.lifecycle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * The six properties of `docs/research/research-oracle.md` §3.2, over **generated** inputs.
 *
 * The example-based tests beside this one establish that each property is decidable from the
 * transcript. This one asks whether it holds for orderings, durations and failures nobody thought
 * of — which is the set an example-based suite is structurally unable to contain, because it is
 * written by the same person who wrote the implementation.
 *
 * ## Why the generator is hand-written
 *
 * A seeded `kotlin.random.Random` and thirty lines, rather than a property-testing library. Three
 * reasons and none is minimalism for its own sake: the generated thing is a *plan*, so shrinking has
 * nothing useful to shrink towards; the seed is printed on failure, which is the one affordance that
 * actually matters for reproducing a case; and a dependency here would need its native targets
 * checked in the registry before it could live in a common test source set — which is a real cost
 * for something this small.
 *
 * ## Why the clock is virtual
 *
 * Generated durations reach tens of seconds. Run in real time this suite would take hours and would
 * be measuring the machine; on `runTest`'s scheduler it takes milliseconds and the assertions are
 * exact rather than approximately true.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ShutdownOrderProperties {
    /**
     * Enough cases to reach the awkward combinations — an empty stage beside one that times out
     * beside one that throws — and few enough that the suite stays a gate rather than a wait.
     */
    private val cases = 200

    @Test
    fun `the recorded transitions are always a prefix of the specified order`() =
        forEachGeneratedPlan { transcript, _ ->
            assertEquals(
                KoreStage.specifiedOrder.filter { it in transcript.order.toSet() },
                transcript.order,
                "the stages ran out of specification order",
            )
            assertEquals(transcript.order.distinct(), transcript.order, "a stage ran twice")
        }

    @Test
    fun `no stage begins before the previous one has ended`() =
        forEachGeneratedPlan { transcript, _ ->
            transcript.stages.zipWithNext { earlier, later ->
                assertTrue(
                    later.enteredAt >= earlier.leftAt,
                    "${later.stage} began at ${later.enteredAt}, before ${earlier.stage} ended at ${earlier.leftAt}",
                )
            }
        }

    @Test
    fun `a stage never runs longer than its own deadline`() =
        forEachGeneratedPlan { transcript, plan ->
            transcript.stages.forEach { run ->
                val declared = plan.single { it.stage == run.stage }.deadline
                assertTrue(
                    run.took <= declared,
                    "${run.stage} took ${run.took} against a deadline of $declared",
                )
            }
        }

    @Test
    fun `a participant that throws never stops the sequence`() =
        forEachGeneratedPlan { transcript, plan ->
            // Whatever failed, every stage still ran. A sequence that aborts halfway leaves open
            // exactly the resources it exists to close.
            assertEquals(plan.map { it.stage }, transcript.order)
        }

    @Test
    fun `the total never exceeds the sum of the deadlines`() =
        forEachGeneratedPlan { transcript, plan ->
            val budget = plan.fold(Duration.ZERO) { sum, stage -> sum + stage.deadline }
            assertTrue(
                transcript.took <= budget,
                "the sequence took ${transcript.took} against a budget of $budget",
            )
        }

    @Test
    fun `concurrent triggers run the sequence exactly once`() = runTest {
        repeat(cases / 4) { case ->
            val seed = case.toLong()
            val plan = generatePlan(Random(seed))
            val sequence = ShutdownSequence(plan, testScheduler.timeSource)

            val transcripts = List(3) { async { sequence.run() } }.awaitAll()

            transcripts.forEach { transcript ->
                assertTrue(transcripts[0] === transcript, "seed $seed: a second trigger ran the sequence again")
            }
            assertEquals(
                plan.map { it.stage },
                transcripts[0].order,
                "seed $seed: the transcript is not the whole sequence",
            )
        }
    }

    /**
     * Runs [check] against [cases] generated plans, and **names the seed when one fails** — which is
     * the whole practical value of generating them. A failure nobody can reproduce is a flake.
     */
    private fun forEachGeneratedPlan(check: (ShutdownTranscript, List<StagePlan>) -> Unit) = runTest {
        repeat(cases) { case ->
            val seed = case.toLong()
            val plan = generatePlan(Random(seed))
            val transcript = ShutdownSequence(plan, testScheduler.timeSource).run()
            try {
                check(transcript, plan)
            } catch (failure: AssertionError) {
                throw AssertionError("seed $seed:\n$plan\n${failure.message}", failure)
            }
        }
    }

    /**
     * A plan with generated shapes.
     *
     * Deliberately includes stages with **no** participants and stages whose participants cannot
     * finish in time: the interesting orderings are at the edges, and a generator that only produces
     * plausible plans tests the cases somebody already thought about.
     */
    private fun generatePlan(random: Random): List<StagePlan> =
        KoreStage.specifiedOrder.map { stage ->
            val deadline = random.nextInt(1, 40).times(100).milliseconds
            StagePlan(
                stage = stage,
                deadline = deadline,
                participants =
                    List(random.nextInt(0, 4)) { index ->
                        generateParticipant(random, "$stage-$index", deadline)
                    },
                // The announce stage is the DWELL one in the real assembly; generated here for both
                // so the properties hold whichever a plan uses.
                duration = if (random.nextBoolean()) StageDuration.DWELL else StageDuration.DEADLINE,
            )
        }

    private fun generateParticipant(
        random: Random,
        name: String,
        deadline: Duration,
    ): ShutdownParticipant =
        when (random.nextInt(4)) {
            0 -> Takes(name, deadline / random.nextInt(2, 6))
            1 -> Takes(name, deadline / 2, throwing = IllegalStateException(name))
            2 -> Hangs(name)
            else -> IgnoresCancellation(name, deadline * random.nextInt(2, 5))
        }
}
