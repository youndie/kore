---
id: B-38
title: "Decide where kore's background work runs on Kotlin/Native"
status: open
priority: P1
size: S
stage: m2-shutdown
blocked_by: [B-04]
---

# B-38 — Decide where kore's background work runs on Kotlin/Native

Picked up while verifying §1.8 during [B-01](B-01-repository-skeleton.md). booblik's native module
records, from compiling against coroutines 1.11.0 rather than from the documentation "which says
otherwise", that **`Dispatchers.IO` is `internal` on Kotlin/Native**. It uses
`newSingleThreadContext` instead, and owns the thread.

kore has the same problem in at least two places and has not solved it:

- the **health refresh loop** ([feature-health-probes](../features/feature-health-probes.md) §4) runs
  a dependency check every couple of seconds, and a check may block underneath a suspending
  signature (Risk 3);
- the **stage machine's parked coroutine** ([feature-ordered-shutdown](../features/feature-ordered-shutdown.md)
  §3), which the signal handler wakes — it must be on a dispatcher that is not the one a blocked
  check has consumed.

- **The decision to make.** `Dispatchers.Default` ties up one of as many threads as there are cores,
  which is exactly wrong for work that may block. `newSingleThreadContext` is what booblik chose and
  it costs a thread per owner. Whether kore owns one thread for all of its background work, or one
  per loop, is the question.
- **Rejected in advance:** discovering this at the first hang. It is cheap to settle now and it
  decides the shape of two features.
- Does **not** cover: the JVM side, where `Dispatchers.IO` exists and is the obvious answer.

- AC: a decision recorded here with the thread count it costs; the stage machine and the health loop
  both take their dispatcher from one place; a test shows a blocking check does not stop the machine.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/`, `kore-core/src/posixMain/`
