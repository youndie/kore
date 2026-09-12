---
id: B-41
title: "Nothing starts the health refresh loop"
status: done
priority: P1
size: S
stage: m3-probes
epic: feature-health-probes
blocked_by: []
---

# B-41 — Nothing starts the health refresh loop

Found while doing [B-38](B-38-native-dispatcher.md), by looking for the loop's dispatcher and
discovering it has no callers: `HealthRegistry.start` is called from no production code, no sample
and no test outside B-38's own.

The consequence is not a dormant feature, it is a wrong answer. `ReadinessGate` built with a registry
asks it for a snapshot; a registry whose loop never ran answers `UNKNOWN — has not run yet` for every
check; `UNKNOWN` is deliberately not healthy ([feature-health-probes](../features/feature-health-probes.md)
§2 rule 5). So a service that wires its dependency checks correctly and forgets one call gets a pod
that **never becomes ready** — and the probe body says `has not run yet`, which reads like a startup
race rather than a missing call.

- **The shape of the defect.** An object that is inert until a second call nobody's type system asks
  for. The same shape as the `written but never called` class generally: it compiles, it reads
  correctly, and the only evidence is a runtime value nobody looks at until a rollout stalls.
- **What was decided, and why none of the three options was.** All three assumed an unstarted
  registry is an error. It is not: `kore-ktor`'s own tests build a registry and drive it with
  `refreshOnce()` — a registry refreshed on someone else's schedule is a legitimate consumer, and a
  type or a check that forbids it would have removed a working pattern to catch a typo. What is
  wrong is not the state but that two different states read identically.

  So the registry now distinguishes *has not finished its first pass* from *nothing is refreshing
  this at all*, and the readiness body names the missing call in the second case. The first still
  reports a startup race, because that one resolves. `stop()` does not un-start a registry.

- AC: a service that registers checks cannot end up with a registry that was never started without
  being told so; a test covers the forgotten call specifically.

## Findings

* **The obvious fix would have broken a working pattern.** Making an unstarted registry
  unrepresentable — `start()` returning a handle that `ReadinessGate` requires — was the first design
  and it is the one this repository's philosophy usually argues for. It fails on the evidence:
  `kore-ktor` drives a registry by hand in six tests, without a loop, and that is a reasonable thing
  for a consumer to do too. The defect is ambiguity, not the state.
* **Every other signal agrees with the misreading.** That is why a better sentence is worth a
  backlog item at all. During a stalled rollout the pod is running, the process is alive, liveness is
  `200`, and readiness is `503` for a reason that reads like a race about to resolve. The one place
  an operator looks is the one place that was misleading.
* **The message states a fact rather than predicting.** It first read `will never run: …`, which a
  later `start(scope)` would make false. Naming the missing call stops the waiting on its own.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/HealthRegistry.kt`,
  `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/ReadinessGate.kt`
