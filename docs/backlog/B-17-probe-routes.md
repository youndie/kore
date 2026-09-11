---
id: B-17
title: "The three routes and the /health alias"
status: done
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

## Iteration 1 — 2026-09-12

**Done.** `installKoreProbes`, plus `StartupGate` and `LivenessGate`. 8 tests; 19 green in `kore-ktor`
on each platform.

**Five of the feature's six scenarios are now `**Automated:**`** — the count went from 1 of 34 to
6 of 34. The sixth needs a real pooled store and is [B-18](B-18-pooled-store-check.md).

One of the five needed a judgement rather than a test. "Liveness survives a dependency outage" ends
in *"and the container is not restarted"*, which no test of a process can observe — it is the
kubelet's consequence of the assertion above it, not a second thing to check. Refusing to mark it
would mean no liveness scenario is ever automatable; marking it silently would be claiming coverage
that does not exist. The scenario now says which clause is a consequence, and is marked.

**`LivenessGate` exists because the endpoint document made a promise with no mechanism behind it.**
It says liveness answers "`503` only for a condition a restart would fix" — and nothing could make it
do so. Now a service can declare itself unrecoverable and nothing in kore calls it, which is the
right way round: kore cannot detect a wedged service, and a failure a restart would *not* fix belongs
in readiness.

**Mutations:**

| Mutation | Result |
|---|---|
| `/health` becomes readiness instead of liveness | 2 of 19 red |
| liveness reads the dependencies | 3 of 19 red |
| startup un-latches during shutdown | 1 of 19 red |
| the startup latch can reopen | **survived — equivalent** |

The fourth is worth the space. Removing the early-out from `StartupGate.completed` changes nothing
observable, because `started` is monotonic: the mutant is *equivalent*, not a gap. Chasing it would
mean writing a test that cannot distinguish the two programs. The line stays, and now says in the
source that it is clarity rather than correctness — **established by mutation instead of assumed**,
which is the useful half.

Not every surviving mutant is a missing test. Recording which is which is what keeps the technique
honest.
