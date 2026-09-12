---
id: B-42
title: "D9 lost its premise: Dispatchers.IO exists on Kotlin/Native"
status: done
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

## The answer

**Both lanes are `Dispatchers.IO` on every target. kore owns no threads.** D9 is rewritten with its
first version withdrawn in place rather than edited away.

The question was whether Kotlin/Native's `Dispatchers.IO` is elastic — whether it grows past a
blocked thread the way the JVM's does, or queues behind a fixed pool. That is measurable, so it was
measured rather than argued:

| Threads deliberately blocked for 3 s | Time to schedule a trivial task on `Dispatchers.IO` |
|---|---|
| 1 | 207 µs |
| 4 | 369 µs |
| 16 | 75 µs |
| 64 | 94 µs |
| 128 | 107 µs |

`linuxX64`, coroutines 1.11.0. It is elastic, and flatly so. Two owned threads would have bought a
named entry in a thread dump at the price of a `close` contract kore could never honour — a lane
outlives every shutdown that might close it.

**What survived unchanged.** That a blocking check must not stall the shutdown, and that this is a
measurement rather than an argument. `BlockingCheckTest` is untouched.

**What the control test became, which is the better half.** It used to put both on
`KoreDispatchers.checks` and rely on that being a thread kore owned. With an elastic lane it had
nothing left to demonstrate, so it now creates its own single-threaded dispatcher — and says the true
thing: the danger is not kore's defaults, it is **a consumer pointing a lane at a dispatcher that
cannot grow**. It also moved from `nativeTest` to `commonTest`, so it now runs on the JVM as well,
which it never did.

## How it was missed, which is the transferable part

B-38 inherited the claim from booblik and **checked it** — by compiling a probe. The probe reproduced
booblik's own missing import, produced booblik's own error, and confirmed booblik's own wrong
conclusion. Verifying a claim by the method that produced it is not verification.

booblik had corrected itself the evening before B-38 ran. The answer was in the repository the claim
came from, and re-deriving was chosen over re-reading.
