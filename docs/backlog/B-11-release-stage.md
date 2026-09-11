---
id: B-11
title: "The release stage: three groups, three deadlines"
status: wip
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
