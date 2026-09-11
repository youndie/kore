---
id: B-17
title: "The three routes and the /health alias"
status: open
priority: P0
size: S
stage: m3-probes
epic: feature-health-probes
blocked_by: [B-16]
---

# B-17 — The three routes and the /health alias

`GET /health/startup`, `/health/ready`, `/health/live`, and `/health` as an alias of liveness.

- **The decision and its reason.** Three questions, three answers (research D4). Liveness reads no
  dependency — a liveness probe that reads the database restarts a healthy pod during a database blip,
  and then every pod, which is how a dependency outage becomes an outage of everything in front of it.
- **Rejected:** renaming `/health` away. Every chart in the portfolio names it; a rename that breaks a
  running deployment to gain a nicer URL is not worth it. The alias is liveness, and the mitigation for
  a chart that points readiness at it is the printed probe block of B-23, not a breaking change.
- Startup is a **latch**: once `200`, never `503` again, including during shutdown. Kubernetes runs
  the startup probe only at startup, so a later `503` is a value nothing reads and everything misreads.
- A failing readiness body names the check and the age of its result. A `503` with no attribution is
  one an operator has to reproduce by hand.

- AC: the scenarios of feature-health-probes §7 hold; the routes match
  [endpoint-kore-admin](../api/endpoint-kore-admin.md) exactly, including the error table.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeRoutes.kt`
