---
id: B-16
title: "The check registry, the cached result and the refresh loop"
status: wip
priority: P0
size: M
stage: m3-probes
epic: feature-health-probes
---

# B-16 — The check registry, the cached result and the refresh loop

Named checks with their own timeout and refresh interval, run on a background loop; the probe route
reads the last result.

- **The decision and its reason.** Risk 3 of [research-architecture](../research/research-architecture.md)
  §3: `withTimeout` does not interrupt a blocking call, and a "suspending" native driver call may be
  blocking underneath. A check on the request path can therefore hang, and a readiness probe that
  hangs has its answer decided by `timeoutSeconds`, whose Kubernetes default is 1. The cache is also
  what makes a two-second readiness period affordable.
- **Rejected:** running checks on the request. Simpler, and it makes the probe's cost proportional to
  how often Kubernetes asks — which is the one variable an operator tunes for unrelated reasons.
- **A stale result is not a healthy result.** Past its refresh budget, readiness is `503` and the body
  says how old the last answer is. A cache that keeps returning the last good value is a probe that
  reports health straight through an outage — which is the exact failure this feature exists to end.
- A check that has never run reports "unknown", which readiness treats as `503`.

- AC: the "a check that hangs produces a stale result" and "a process that has not run its first check
  is not ready" scenarios of [feature-health-probes](../features/feature-health-probes.md) §7 hold.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/`
