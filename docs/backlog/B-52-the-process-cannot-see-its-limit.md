---
id: B-52
title: "A Kotlin/Native process cannot see the limit it will be killed on"
status: done
priority: P2
size: M
stage: m6-release
epic: feature-memory-budget
blocked_by: []
---

# B-52 — A Kotlin/Native process cannot see the limit it will be killed on

The JVM has read the container memory limit since 10 and sizes its heap from it. A Kotlin/Native
binary has nothing of the kind: its GC target is a compiler default or a number somebody typed into
a build file, and neither has any relationship to what the chart says. Every service in the
portfolio that has met this met it as an `exit=137` with nothing in its own logs.

It arrived as a gap in the `native-service-bootstrap` skill, which had to write the sentence *"kore
does not read cgroup limits today"* beside a measurement recipe — a skill is the one place that can
describe a mechanism and enforce nothing, so the mechanism belongs here.

## What is built

`containerMemoryBudget()` in `kore-core`, answering `Bounded` / `Unbounded` / `Unavailable`; the
whole cgroup decision in `commonMain` behind one file-reading function, so every branch is a common
test rather than a container; `--print-config` printing one line with the answer; and
`applyHeapCeiling()` as the lever a service calls with its own measured number.

**kore does not act on the budget**, and that is the design rather than an unfinished half. What
fraction of a limit should be heap depends on how much of the process is *not* heap, and on this
platform that remainder is usually the larger term. Picking a fraction in a library picks it for
every consumer at once. The open measurement is sborka's Brief C.

## Two things the implementation found

**The root cgroup has no `memory.max`.** The kernel does not create one for a cgroup that cannot be
limited. `/sys/fs/cgroup/memory.max` is therefore absent on an ordinary Linux host and present inside
a container, and the first version of the Linux test — which guarded on that file — reported "no
cgroup here" on the machine this repository builds on. Walking `/proc/self/cgroup`'s path and taking
the tightest readable limit answers both cases, and it is also correct for the case that motivated
it: a limit on an ancestor cgroup is enforced exactly like one on the leaf.

**`targetHeapBytes` is the wrong setting.** It is the one whose name fits, and with `GC.autotune` on
— the default — the runtime recomputes it from the live set after every collection. Measured on
linuxX64, Kotlin 2.4.10, 2026-09-15: a target set to **201 326 592** read back as **5 242 880** after
four collections, while `GC.maxHeapBytes` was still 201 326 592. The test carries a control that both
setters took effect before the collections, so it cannot pass against a runtime that ignored the
target outright.

- AC: a service on Linux can ask kore what it will be killed on and get the enforced number, an
  unreadable cgroup is never rendered as "no limit", and nothing in kore changes a GC setting by
  itself. **Met.**
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/runtime/CgroupMemory.kt`,
  `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/runtime/HeapCeiling.native.kt`
