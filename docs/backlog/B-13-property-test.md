---
id: B-13
title: "The property test over the stop order, proved by mutation"
status: wip
priority: P0
size: M
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-04]
---

# B-13 — The property test over the stop order, proved by mutation

The six properties of [research-oracle](../research/research-oracle.md) §3.2, over generated
durations, failures and participant counts, on every target kore publishes.

- **The decision and its reason.** A property test over a sequence is easy to write so that it holds
  for any sequence. So the test is itself under test: the six mutations named in research-oracle §3.3
  are applied and the suite **must** go red for each. A mutation that survives is a gap in the
  property, and the item is not done until it fails.
- **Rejected:** scripted ordering tests. They cover the orderings somebody thought of, which is the
  set that was already in the implementer's head.
- Does **not** cover: the pre-drain wait set to zero — a property test cannot see a legal duration.
  That gap is named in research-oracle §3.3 and covered by assertion A5 in the end-to-end oracle
  instead. Both gates exist because neither replaces the other.

- AC: every property holds; every named mutation fails the suite; the mutation run is recorded in
  this item.
- Anchors: `kore-core/src/commonTest/kotlin/io/github/youndie/kore/lifecycle/`
