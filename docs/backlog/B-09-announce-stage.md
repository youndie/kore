---
id: B-09
title: "The announce stage: readiness false, then wait"
status: open
priority: P0
size: S
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-04]
---

# B-09 — The announce stage: readiness false, then wait

Flip the readiness gate, then wait the configured pre-drain delay before anything else happens.

- **The decision and its reason.** Research §1.10: during a rollout the control plane has already
  marked the terminating endpoint `ready: false`, so flipping readiness buys the truth for anything
  that polls the pod directly. What actually stops traffic arriving after `SIGTERM` is the *time* it
  takes a rule change to reach every node — so the wait is the stage that does the work, and giving
  it a name is what stops it being deleted as pointless.
- **Rejected:** hanging this off `ApplicationStopPreparing`. Research §1.2: on CIO that event fires
  after the listening socket has stopped accepting, so a probe arriving afterwards gets a connection
  refused rather than a 503, and the drain deadline is already counting.
- Does **not** cover: the default's value, which is a hypothesis until [B-20](B-20-pre-drain-default.md).

- AC: assertion A4 and A5 of research-oracle §2.3 hold against the sample on both platforms.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/`
