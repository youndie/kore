---
id: B-25
title: "Decide what kore assumes when the grace period is not declared"
status: question
priority: P2
size: XS
stage: m4-config
blocked_by: [B-12]
---

# B-25 — Decide what kore assumes when the grace period is not declared

Open question attached to Risk 4 of [research-architecture](../research/research-architecture.md) §3.
kore refuses a set of deadlines that cannot fit the grace period — and outside Kubernetes nothing
tells it what the grace period is.

- **The hypothesis.** Assume the Kubernetes default of 30 s, say so in `--print-config` with origin
  `default`, and let a service override it. An assumption that is printed is one somebody can
  contradict; an assumption that is not printed is a constant.
- **Rejected:** refusing to start without one. A library that cannot run outside Kubernetes because it
  was not told a Kubernetes number is a library with a bad dependency on a deployment shape.
- **Rejected:** skipping the fit check when the period is unknown. That removes the guard in exactly
  the environment where nothing else provides one.
- This is a `question` rather than `open` because the answer changes behaviour a consumer can observe,
  and it is the owner's to pick.

- AC: a decision recorded here and implemented in [B-12](B-12-deadlines-must-fit.md); `--print-config`
  shows the assumed value and its origin.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/`
