---
id: B-11
title: "The release stage: three groups, three deadlines"
status: done
priority: P0
size: M
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-04]
---

# B-11 — The release stage: three groups, three deadlines

Consumers, then pools, then telemetry — each group with its own deadline, run **after**
`EmbeddedServer.stop` has returned.

- **The decision and its reason.** Research §1.1 consequence 3: on Kotlin/Native `ApplicationStopping`
  fires before the drain, so a stage that must run after the drain cannot be a Ktor subscriber at all.
  Per-group deadlines because a single budget is one the first group can spend entirely — a flush
  against a dead broker would otherwise leave the drain with nothing.
- **Rejected:** one deadline for the whole release, and running the groups concurrently. A consumer
  mid-handler needs its pool, so closing the two at once is a race with a 500 in it.
- Does **not** cover: what a participant *is* for booblik ([B-15](B-15-booblik-adapter.md)) or for a
  pool ([B-18](B-18-pooled-store-check.md)).
- A participant that throws is recorded and the sequence continues: a shutdown that aborts halfway
  leaves open exactly what it exists to close.

- AC: the three groups run in order with their own deadlines, over the stage machine. The two
  scenarios of [feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §5 both end in
  "and the process still exits", which no test of a machine can observe — that half is
  [B-39](B-39-kore-wired-sample.md).
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/`

## Iteration 1 — 2026-09-12

**Done.** `shutdownSequence { }`, `ShutdownPlanBuilder` and `ShutdownDeadlines` — the assembly a
consumer actually touches, with every default carrying its derivation. 8 tests, 24 green on each
platform.

The order is not configurable: `consumer` before `pool` because a consumer mid-handler still needs
one, `telemetry` last because it is the only group whose job is to report on the others, and `drain`
is its own stage rather than a group because it must run before everything the draining requests
need. A test registers them in the wrong order and asserts the right one.

**Writing the tests found a defect in the stage machine, and it was the predictable one.**
`runStage` returned immediately for a stage with no participants — sensible for a pool group with no
pools, and it silently deleted the **announce** stage, whose entire job is to wait. The test asked
for seven seconds and got 5.462 µs.

So `StagePlan` now carries what its duration *means*: `DEADLINE` bounds work, `DWELL` is time to
spend. Exactly one stage is `DWELL`, and the feature document says why. Mutation: removing the dwell
turns 1 of 24 red.

**And a test failure that was the test's fault, worth the same attention.** The second failure —
a release group recorded as 4.8 ms instead of 3 s — was `shutdownSequence` not accepting a
`TimeSource`, so the transcript was stamped from the real clock while the delays ran on `runTest`'s
virtual one. A test failing against a mechanism that works. The builder takes one now, with the
reason on the parameter.

**Not done:** the grace-period fit check, which is [B-12](B-12-deadlines-must-fit.md) and now has a
`total` to compare against; and the end-to-end proof, [B-39](B-39-kore-wired-sample.md).
