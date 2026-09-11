---
id: B-28
title: "The three agents in one call, with three shutdown contracts"
status: open
priority: P1
size: M
stage: m5-wiring
epic: feature-observability-wiring
blocked_by: [B-11, B-21, B-37]
---

# B-28 — The three agents in one call, with three shutdown contracts

Install tracy, metrik and katcher from one call, configured through the schema, with the shutdown
treatment each actually needs.

- **The decision and its reason.** Research §1.6, read in the agents: tracy can flush and is never
  asked to, metrik stops itself without flushing, katcher cannot stop at all. A service that wires
  them by copying an example gets whichever of the three behaviours the example happened to get right.
- **A half-configured agent is a refusal at startup.** All three answer a missing value by doing
  nothing, in three different ways — "a deployment that believes it is observed and is not". The rule
  is taken from the one place in the portfolio that already gets it right, not invented.
- **Installing a buffer without the thing that empties it must be impossible through kore.** tracy's
  plugin and its delivery are two objects; installing only the first logs into memory and reports
  nothing.
- **Blocked on [B-37](B-37-agents-not-on-central.md), found during B-01:** none of the three agents
  is on Maven Central, so there is no way to depend on them that a consumer outside the portfolio can
  resolve. That is a decision before it is a wiring job.
- Does **not** cover: the losses kore cannot fix from outside — metrik's open window, katcher's
  un-stoppable scope. Those are documented in
  [feature-observability-wiring](../features/feature-observability-wiring.md) §7 and filed in
  [B-32](B-32-file-upstream.md).

- AC: the scenarios of feature-observability-wiring §5 hold, including that an unreachable tracy
  endpoint does not delay the exit past the telemetry deadline.
- Anchors: `kore-observability/src/commonMain/kotlin/io/github/youndie/kore/observability/`
