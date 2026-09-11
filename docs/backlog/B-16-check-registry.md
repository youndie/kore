---
id: B-16
title: "The check registry, the cached result and the refresh loop"
status: done
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

## Iteration 1 — 2026-09-12

**Done.** `HealthCheck`, `HealthRegistry` and `HealthStatus`. 8 tests; 38 green on each platform.

**`UNKNOWN` is a first-class status rather than a flavour of unhealthy**, and it carries both halves
of the rule this item exists for: a process whose first refresh has not finished has not earned a
`200`, and a result past its budget stops counting as an answer. A cache that keeps returning the
last good value is a probe that reports health straight through an outage — the failure the feature
was written to end, not a corner case.

`staleAfter` is deliberately **not** the refresh interval: a check that overruns leaves the previous
result in place, and without a separate budget it would go on being served as though it were current.

**Mutations, all killed, all checked to be test failures rather than compilation failures:**

| Mutation | Result |
|---|---|
| a stale result keeps being served | 1 of 38 red |
| a check that never ran counts as healthy | 1 of 38 red |
| a timed-out check counts as healthy | 1 of 38 red |
| `allHealthy` ignores `UNKNOWN` | 2 of 38 red |

The last is the one worth having: treating "we have not asked yet" as "not a failure" is the most
natural way to write that line, and it is exactly wrong.

**No scenario becomes `**Automated:**`.** All three the registry touches are written against
`GET /health/ready` — a route, not a registry — so the tests cover a part and the scenarios stay
manual until [B-17](B-17-probe-routes.md). A scenario ticked because most of it is covered is how a
suite starts reporting more than it checks.

**Not done:** the timeout does not interrupt a blocking call, and this item does not pretend
otherwise — a check that blocks underneath a suspending signature still holds its thread. That is
Risk 3 and [B-38](B-38-native-dispatcher.md), named in the source where somebody will read it.
