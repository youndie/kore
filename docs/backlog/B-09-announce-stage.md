---
id: B-09
title: "The announce stage: readiness false, then wait"
status: wip
priority: P0
size: S
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-04, B-16]
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

- AC: the stage flips the readiness gate and then waits, asserted over the transcript of the stage
  machine. **The end-to-end proof — A4 and A5 against a running container — is
  [B-39](B-39-kore-wired-sample.md)**, because it needs a sample that uses kore and there is not one:
  `samples/service` is the control and its whole value is being wired the ordinary way.
- Also `blocked_by` [B-16](B-16-check-registry.md), added 2026-09-12: a stage that sets readiness
  false needs somewhere for readiness to live. The original `blocked_by` named only the stage machine
  and would have had this item picked before anything it could announce existed.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/`
