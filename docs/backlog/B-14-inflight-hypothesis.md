---
id: B-14
title: "Settle what disposeAndJoin does to an in-flight call on Native"
status: open
priority: P1
size: S
stage: m2-shutdown
blocked_by: [B-03]
---

# B-14 — Settle what disposeAndJoin does to an in-flight call on Native

Research [§1.1](../research/research-architecture.md) carries an explicit hypothesis: it is read
directly that `ApplicationStopping` fires before the drain on Kotlin/Native, and **not** read whether
an in-flight CIO call is a child of `applicationJob` and therefore cancelled by `disposeAndJoin()`, or
merely deprived of the plugins it needs.

- **The decision and its reason.** The consequence for kore is the same either way — a request in
  flight during `ApplicationStopping` is not safe — but the failure it produces differs, and a
  document that states the wrong mechanism sends the next reader looking in the wrong place.
- **Rejected:** leaving the hypothesis open indefinitely. A hypothesis with no address is a guess
  that has been promoted by age.
- Does **not** cover: changing kore's design. This is a correction to a document.

- AC: the oracle run of [B-03](B-03-negative-control.md) produces evidence of which it is; research
  §1.1 is amended at the point of divergence, keeping what it used to say and why that was wrong.
- Anchors: `docs/research/research-architecture.md`
