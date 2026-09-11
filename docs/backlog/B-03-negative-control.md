---
id: B-03
title: "The negative control: run the oracle against the unfixed shape"
status: open
priority: P0
size: M
stage: m1-oracle
epic: feature-ordered-shutdown
blocked_by: [B-05, B-06]
---

# B-03 — The negative control: run the oracle against the unfixed shape

Before kore exists, run the oracle of [research-oracle](../research/research-oracle.md) §2 against a
service wired the ordinary way — one `ApplicationStopping` subscriber, one `/health`,
`embeddedServer(...).start(wait = true)` — on both targets.

- **The decision and its reason.** Every fact in research §1.1 predicts this breaks on Kotlin/Native
  and survives on the JVM. If it does not break, one of three things is true: the fact is wrong, the
  load is not load, or the harness cannot see the failure. Each is cheaper to learn in week one than
  after five features rest on the premise.
- **Rejected:** taking the source reading as sufficient. A reproducible symptom is not a mechanism,
  and a mechanism read in source is not a symptom; the library's whole argument needs both.
- Does **not** cover: fixing anything. The output is a measurement and, if it contradicts research
  §1.1, a correction written *at the point of divergence* in that document.

- AC: a run of the oracle against the unfixed sample on `jvm` and `linuxX64`, with the result written
  into `docs/research/measurements-<date>/`; research §1.1 either stands unchanged or carries a
  correction naming this run.
- Anchors: `samples/`, `docs/research/research-architecture.md`
