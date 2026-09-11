---
id: B-04
title: "The stage machine and its recorded transitions"
status: done
priority: P0
size: M
stage: m1-oracle
epic: feature-ordered-shutdown
---

# B-04 — The stage machine and its recorded transitions

The data structure everything else hangs off: named stages, per-stage deadlines, participants, and a
record of every transition entered and left.

- **The decision and its reason.** It is written **before** any stage does real work, because a
  sequence retrofitted with observability is a sequence whose test was written to match it. The
  recorded transitions are the thing the property test of
  [research-oracle](../research/research-oracle.md) §3 asserts over — a test cannot assert over a
  lambda in `ApplicationStopping`.
- **Rejected:** a list of `suspend () -> Unit` run in order. It gives no way to express a per-stage
  deadline, no way to record what happened, and no way to fail a *group* while continuing the
  sequence.
- Does **not** cover: signals (B-08), the Ktor wrapper (B-10) or any real participant.

- AC: the machine runs a generated sequence with no server anywhere near it; the transitions it
  records are enough to decide every property in research-oracle §3.2.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/`

## Iteration 1 — 2026-09-11

**Done.** `KoreStage`, `StagePlan`, `ShutdownParticipant`, `ShutdownTranscript`, `ShutdownSequence`.
Eleven example-based tests, one per property of research-oracle §3.2 plus the plan's own refusals,
green on `jvm` **and** `linuxX64` — which matters more than usual here, because the thing under test
is a claim about two platforms behaving alike.

| Checked | How |
|---|---|
| the tests ran | result XML: `jvmTest` 12 cases, `linuxX64Test` 13, zero failures |
| each property is decidable from the transcript | one test per property; property 5 needed two, because the interesting case is a participant that ignores cancellation |
| the tests earn their lines | three mutations, killed: reversing `specifiedOrder` (2 of 13 red), joining the stage scope instead of detaching it (1 red), removing the once-only guard (1 red). Tree clean after each |

**Two things the implementation decided that the documents had left ambiguous**, both written back
into the documents at the point of divergence rather than absorbed:

1. **Seven stages, not five.** The feature document tells the story in five with `release` holding
   three groups — and `release` carries three deadlines, so the unit that carries a deadline is the
   group. Property 1 asserts the transcript is a prefix of *one unambiguous list*, so `KoreStage` has
   seven entries. The five-stage grouping stays as the explanation. Research D2 amended;
   feature §3 now says both.
2. **A stage detaches its participants instead of joining them.** Structured concurrency would join
   the children on the way out, which is exactly the wait the deadline exists to avoid: one
   participant that ignores cancellation and the whole time bound is a lie. So the bound is a promise
   about **waiting**, not about stopping. This is the mutation that would otherwise have survived
   into B-13 — research-oracle §3.2 property 5 now names the uncooperative case explicitly, because a
   generator that never produces it satisfies the property with an implementation that does not hold
   it.

**One scenario became automated**, and only one: "two signals run the sequence once". The other two
the machine touches both end in "and the process still exits", which no test of a machine can
observe; they stay unmarked until the oracle of research-oracle §2 can run them. A scenario ticked
because *most* of it is covered is how a suite starts reporting more than it checks.

**A gotcha, now in `CLAUDE.md`:** Kotlin/Native refuses a comma inside a backticked test name
(`Name contains illegal characters: ","`) and the JVM target compiles it happily — so the failure
arrives from a target nobody was thinking about. Two test names had to be reworded.

**Deliberately not done:** the generative property test (B-13), signals (B-08), the Ktor wrapper
(B-10), any real participant (B-11, B-15). The machine does not call `exit()` either — a library that
ends the process is one a test cannot run twice, so `EXIT` is a recorded marker and the caller acts
on it.
