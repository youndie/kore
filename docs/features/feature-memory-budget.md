---
id: feature-memory-budget
title: The budget the process is judged against
type: feature
status: active
owner: unassigned
involved_services:
  - kore-library
client_entries: []
api: []
tags: [runtime, memory, cgroup, kotlin-native]
---

# The budget the process is judged against

> **Built.** `containerMemoryBudget()` reads the cgroup on the Linux targets and the JVM, declares
> itself unavailable on macOS native, and `--print-config` prints one line with the answer.
> `applyHeapCeiling()` is the lever, and **nothing in kore calls it** — §2 rule 5 says why.

## 1. Overview

A Kotlin/Native server does not know how much memory it is allowed to use. The JVM has read the
container limit since 10 and sizes its heap from it; a native binary has no equivalent, so its GC
target is whatever the compiler defaulted to, and the only number in the system that knows what the
chart says is the one the kernel kills on.

kore reads that number and hands it over. `containerMemoryBudget()` answers with the limit, with "no
limit", or with "nobody could tell and here is what was tried" — three cases, because a deployment
that reads the third as the second has been told it has room it does not have. `--print-config`
prints it, which is where the difference between a laptop and a pod becomes visible at the moment
somebody is already asking why they differ.

## 2. Business rules

1. **Three answers, never two.** `Bounded`, `Unbounded`, `Unavailable`. An unreadable cgroup is not
   an absent limit, and `render()` never spells the third like the second — there is a test whose
   whole job is that sentence.
2. **The tightest limit in the chain is the answer.** A limit on any ancestor cgroup is enforced
   exactly like one on the process's own, which is what a pod-level limit over several containers
   is. Reading only the leaf reports "no limit" for a service that has one.
3. **Both layouts are read.** cgroup v2 (`memory.max`) first, v1 (`memory.limit_in_bytes`) after, and
   v1's page-aligned `LONG_MAX` is "no limit" rather than nine exabytes.
4. **An absent capability is declared, not discovered.** macOS native and a non-Linux JVM answer
   `Unavailable` with the reason spelled out, rather than by failing to open four files — the same
   rule the environment walk follows.
5. **kore reads and does not act.** Nothing here sets a GC parameter on its own. What fraction of a
   container limit should be heap depends on how much of the process is *not* heap — on
   Kotlin/Native the allocator's per-thread pages, glibc's arenas and a driver's native half are
   usually the larger term — and a library that picked a fraction would be picking it for every
   consumer at once, with none of their measurements. The service that measured it passes the number
   to `applyHeapCeiling()`.
6. **The lever is the ceiling, not the target.** Measured, §5.

## 3. Why this is not `Runtime.maxMemory` with more steps

On the JVM the two questions look alike and are not. `maxMemory()` is a heap; the cgroup limit
covers the heap *plus* metaspace, thread stacks, direct buffers and whatever a native library
allocates — and only the second is what the kernel kills on. kore answers the second on both
platforms, so that a chart value and a process's idea of it can be compared at all.

## 4. Code anchors

| Service | Code |
|---|---|
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/runtime/CgroupMemory.kt` — the whole decision, behind one file-reading function |
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/runtime/MemoryBudget.kt` — the three answers and the rendering |
| kore-library | `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/runtime/HeapCeiling.native.kt` — the lever |

## 5. Two findings that shaped the code

**The root cgroup has no `memory.max`.** The kernel does not create one for a cgroup that cannot be
limited, so `/sys/fs/cgroup/memory.max` is absent on an ordinary Linux host and present inside a
container, where the mount is the container's own cgroup. The first version of the Linux test
guarded on that file and reported "no cgroup on this host" on the machine the portfolio builds on.
The walk up `/proc/self/cgroup`'s path is what makes the answer the same in both places.

**`targetHeapBytes` is not where a container limit goes.** It is the setting whose name fits the
intention, and with `GC.autotune` on — the default — the runtime recomputes it from the live set
after a collection. Measured on linuxX64 with Kotlin 2.4.10 on 2026-09-15: the target set to
**201 326 592** read back as **5 242 880** after four collections, while `GC.maxHeapBytes` was still
201 326 592. So kore's lever is the ceiling, and the test carries a control — both setters are
asserted to have taken effect *before* the collections — so that it cannot pass against a runtime
that ignored the target outright.

## 6. Scenarios (BDD)

### Scenario: a service in a container learns its limit
* **Given:** the process is in a cgroup v2 whose `memory.max` reads `201326592`
* **When:** it calls `containerMemoryBudget()`
* **Then:** the answer is `Bounded(201326592)` and names the file it came from
* **Automated:** `CgroupMemoryTest`, and against the real filesystem by `MemoryBudgetLinuxTest`

### Scenario: a limit on an ancestor is the one that will be enforced
* **Given:** the container's own cgroup says `max` and the pod's cgroup one level up says `104857600`
* **When:** the budget is read
* **Then:** the answer is `104857600`, named with the ancestor's path
* **And:** an implementation that reads only the leaf reports "no limit" here
* **Automated:** `CgroupMemoryTest`

### Scenario: an unreadable cgroup is not an absent limit
* **Given:** nothing under `/sys/fs/cgroup` can be read
* **When:** the budget is read and rendered
* **Then:** the answer is `Unavailable` and the rendering says `unknown`
* **And:** it does **not** say "no limit"
* **Automated:** `CgroupMemoryTest`

### Scenario: cgroup v1 states "no limit" as a number
* **Given:** `memory.limit_in_bytes` holds `9223372036854771712`
* **When:** the budget is read
* **Then:** the answer is `Unbounded`, not a limit of nine exabytes
* **Automated:** `CgroupMemoryTest`

### Scenario: macOS native says it cannot answer
* **Given:** a build for `macosArm64`
* **When:** the budget is read
* **Then:** it is `Unavailable` and the reason names the platform rather than four missing files
* **Automated:** the actual is the declaration; `PrintConfigTest` asserts the printed line never
  claims both

### Scenario: the printed configuration carries the budget
* **Given:** a usable configuration
* **When:** the binary is run with `--print-config`
* **Then:** the output has a `memory budget:` line
* **And:** that line never says "no limit" and "unknown" at once
* **Automated:** `PrintConfigTest`

### Scenario: the real binary reads the limit the runtime imposed
* **Given:** the native sample image run with `--memory=192m`
* **When:** it is run with `--print-config`
* **Then:** the output says `memory budget: 192 MiB`
* **And:** it says neither `no limit` nor `unknown`
* **Automated:** `samples/oracle` `configRefusal` — the only check here that reads a cgroup the tests
  did not write. Controlled in both directions on 2026-09-15: the same image with no limit printed
  `no limit`, with `--memory=64m` printed `64 MiB`, with `--memory=1g` printed `1 GiB`

### Scenario: a service applies its own measured fraction
* **Given:** a service that has measured what fraction of its limit should be heap
* **When:** it calls `applyHeapCeiling(bytes)`
* **Then:** `GC.maxHeapBytes` is set and the outcome reports what the runtime ended up with
* **And:** the same call on the JVM reports `applied = false` and names `-XX:MaxRAMPercentage`
* **Automated:** `HeapCeilingNativeTest`

## 7. Out of scope

* **Choosing the fraction.** Rule 5. The open measurement is sborka's Brief C, whose subject is
  exactly what a Kotlin/Native service's resident memory is made of under a limit.
* **Acting on the budget by itself.** No automatic ceiling, no automatic GC interval, no refusal to
  start under a small limit. Every one of those is a policy a consumer would have to switch off.
* **CPU limits.** The same file tree holds `cpu.max`, and nothing in kore has a use for it yet;
  adding it "while we are here" would be a capability with no consumer to keep it honest.

## 8. Quirks

* **`/sys/fs/cgroup/memory.max` does not exist on an unconstrained host** — §5. Present in a
  container, absent at the root of a host's own hierarchy.
* **`sysfs` and `procfs` files report a size of zero**, so a read that trusts the size returns
  nothing. The native reader reads until EOF for that reason and caps at 64 KiB so that a path that
  turned out to be a pipe cannot hang a startup.
* **The JVM's own container support answers a different question** — §3.
