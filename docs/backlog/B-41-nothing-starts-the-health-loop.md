---
id: B-41
title: "Nothing starts the health refresh loop"
status: open
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
- **Not decided here:** whether the fix is that `ReadinessGate` refuses a registry that is not
  running, that the registry starts itself on first `snapshot()`, or that the wiring entry point owns
  the call. The first makes the failure loud at construction; the second makes the API
  forgiving and hides a thread being created from a probe handler; the third only works once
  [B-30](B-30-entry-point-question.md) says whether kore owns the entry point.

- AC: a service that registers checks cannot end up with a registry that was never started without
  being told so; a test covers the forgotten call specifically.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/HealthRegistry.kt`,
  `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/ReadinessGate.kt`
