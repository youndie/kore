package io.github.youndie.kore.lifecycle

import io.github.youndie.kore.health.ReadinessGate

/**
 * The first thing that happens, and it takes microseconds.
 *
 * The *waiting* is the announce stage's real work — what stops traffic arriving after `SIGTERM` is
 * the time a rule change takes to reach every node, not this flip (research §1.10). The stage is
 * marked [StageDuration.DWELL] so the wait survives; this participant is only what makes the flip
 * true before it starts.
 *
 * It is a participant rather than a line inside the machine so that the transcript records it: a
 * stage that flipped nothing and a stage that flipped a gate look the same from outside otherwise.
 */
public class AnnounceNotReady(
    private val gate: ReadinessGate,
) : ShutdownParticipant {
    override val name: String = "readiness"

    override suspend fun stop() {
        gate.beginShutdown()
    }
}
