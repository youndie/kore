---
id: B-42
title: "D9 lost its premise: Dispatchers.IO exists on Kotlin/Native"
status: wip
priority: P1
size: S
stage: m2-shutdown
blocked_by: []
---

# B-42 — D9 lost its premise: `Dispatchers.IO` exists on Kotlin/Native

[Research §1.14](../research/research-architecture.md) was refuted the day it was written. `Dispatchers.IO`
is an **extension property** on Kotlin/Native; without `import kotlinx.coroutines.IO` the compiler
resolves the internal member of the same name and says `"it is internal"`. With the import it
compiles for `linuxX64` and `macosArm64` and runs.

So [D9](../research/research-architecture.md) — two lanes, two threads kore owns on Native — rests on
a reason that no longer exists.

- **What is *not* in doubt.** A dependency check can block its thread, and a blocked check must not
  stall the shutdown sequence. That was **measured**, not assumed: on one shared lane the sequence
  takes 2.001 s against a check holding a thread for 2 s (`SharedLaneControlTest`), and with two it
  returns in milliseconds (`BlockingCheckTest`). Those tests stay whatever this decides.
- **The decision to make.** Whether `KoreDispatchers.checks` and `.lifecycle` should be
  `Dispatchers.IO` on Native as they already are on the JVM — an elastic pool grows past a blocked
  thread, which is the separation the two lanes were built to provide by hand — or whether kore keeps
  owning threads for a reason it can still state. Owning them buys a named thread in a dump
  (`kore-checks` parked in a socket read names its own problem) and costs two threads per process;
  `Dispatchers.IO` costs nothing and names nothing.
- **Rejected in advance:** leaving D9's text as written. A decision whose stated reason is false is
  worse than an unexplained one, because the next reader builds on it.
- Does **not** cover: the JVM side, which was already `Dispatchers.IO` and is unaffected.

- AC: D9 is rewritten to stand on a reason that survives checking, or withdrawn and replaced; if the
  two threads stay, the argument for them is stated in terms of what they buy rather than what the
  platform lacks; `SharedLaneControlTest` still demonstrates whatever the answer is, or is replaced
  by a test that does.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/concurrent/`,
  `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/concurrent/`

## How it was missed, which is the transferable part

B-38 inherited the claim from booblik and **checked it** — by compiling a probe. The probe reproduced
booblik's own missing import, produced booblik's own error, and confirmed booblik's own wrong
conclusion. Verifying a claim by the method that produced it is not verification.

booblik had corrected itself the evening before B-38 ran. The answer was in the repository the claim
came from, and re-deriving was chosen over re-reading.
