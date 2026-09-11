---
id: B-04
title: "The stage machine and its recorded transitions"
status: open
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
