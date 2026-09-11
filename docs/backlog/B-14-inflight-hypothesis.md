---
id: B-14
title: "Settle what disposeAndJoin does to an in-flight call on Native"
status: done
priority: P1
size: S
stage: m2-shutdown
blocked_by: [B-06]
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

## Answered — 2026-09-11, by [B-06](B-06-oracle-harness.md) rather than by B-03

**The hypothesis was wrong, and so was the sentence that made it look harmless.**

Research §1.1 asked whether an in-flight CIO call is cancelled by `disposeAndJoin()` on Kotlin/Native
— and said "the consequence is the same for kore either way". Run with a grace period long enough to
see anything, **all eight in-flight requests completed on native**. Whatever
`applicationJob.cancelAndJoin()` cancels, the in-flight calls are not it.

Why it could not be answered earlier: at Ktor's default `shutdownGracePeriod` of **one second**,
every 3-second request is cut off on both platforms, and the ordering question is invisible
underneath a budget smaller than the work. The first run said "8 of 8 cut off" on native and would
have been written up as confirmation.

What stands: the ordering itself, read in the source. What changes: the danger is not that Ktor kills
the request, it is that **a subscriber closing a pool kills it** while the request is still being
served. A smaller claim, and a true one. §1.1 and §1.13 carry it.
