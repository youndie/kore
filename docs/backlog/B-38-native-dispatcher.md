---
id: B-38
title: "Decide where kore's background work runs on Kotlin/Native"
status: done
priority: P1
size: S
stage: m2-shutdown
blocked_by: [B-04]
---

# B-38 — Decide where kore's background work runs on Kotlin/Native

> **Its premise was false, and [B-42](B-42-dispatchers-io-exists-on-native.md) corrected it the same
> day.** Everything below about `Dispatchers.IO` being `internal` on Kotlin/Native is **wrong**: it is
> an extension property needing `import kotlinx.coroutines.IO`, it is available, and it is elastic
> there. The decision this item reached — two named lanes — survived; *kore owning a thread per lane*
> did not, and kore now owns none. The text is left as written because how the mistake was made is
> the useful part: see B-42's last section.

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
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/concurrent/`,
  `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/concurrent/`

## The decision

**Two lanes. On Kotlin/Native that costs two threads; on the JVM, none.** Recorded in full as
research D9, with the fact that forced it as §1.14.

`KoreDispatchers.lifecycle` runs the stage machine and the signal watch; `KoreDispatchers.checks`
runs dependency checks. Native backs each with a `newSingleThreadContext` created on first use — so a
binary with no health loop pays for one thread, not two — and never closes them: closing a lane
during a shutdown would remove the lane the shutdown is running in. The JVM uses `Dispatchers.IO` for
both, because it is elastic and already provides the separation the lanes exist for.

One lane per check was rejected: it costs a thread per dependency and buys nothing for shutdown,
which is the only thing that must not wait. A check stalled behind another is already reported as a
stale answer with its age.

## Findings

* **The premise was inherited and was checked anyway.** booblik's note that `Dispatchers.IO` is
  `internal` on Kotlin/Native came from compiling against coroutines 1.11.0. This repository pins the
  same version, so the claim was likely — and it was still verified here, by compiling a two-line
  file, because a borrowed finding is a hypothesis. The compiler agreed, in its own words.
* **The mitigation for Risk 3 covered half the problem.** Caching the result keeps a blocking check
  off the *probe's* thread and says nothing about the thread the check is holding. Risk 3 is amended
  at the point of divergence rather than replaced.
* **Nothing starts the health refresh loop.** Found by looking for its dispatcher and finding no
  callers. It is a wrong answer rather than a dormant feature — a registry that never ran reports
  `UNKNOWN`, and `UNKNOWN` is not ready. Raised as [B-41](B-41-nothing-starts-the-health-loop.md)
  rather than fixed here: which of the three fixes is right depends on
  [B-30](B-30-entry-point-question.md).
