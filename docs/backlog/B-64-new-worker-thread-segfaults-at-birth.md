---
id: B-64
title: "A Kotlin/Native worker thread started during an early shutdown segfaults on its first instructions"
status: open
priority: P3
size: M
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-64 — A Kotlin/Native worker thread started during an early shutdown segfaults on its first instructions

Split out of [B-63](B-63-sigterm-right-after-start-segfaults.md), which was filed for this crash and
found a different defect instead. The sample's native debug binary, signalled the moment `/health`
first answers with `--pre-drain=100`, sometimes dies with `SIGSEGV`, exit 139.

## What is known

- **One signature, fourteen times.** An `LD_PRELOAD` reporter (a `SIGSEGV` handler writing the
  faulting address, `rip` and raw return addresses) caught 14 crashes. Every one had fault address
  `0x108` and `rip=0x444fbd`, in the runtime's `(anonymous namespace)::workerRoutine(void*)`.
- **Where, in the instructions.** Just after `initRuntime()` for the new thread, `workerRoutine` loads
  a pointer from the `Worker` object at `+0xf8` and exchanges a word at `+0x108` through it. The
  pointer is null. So a worker thread, on its first instructions, finds a field it needs unset.
- **When.** In both crashes where the main thread's stack was also captured (a real-time signal from
  the reporter), main was inside `startKoreSample` → `runBlocking`, i.e. running the shutdown
  sequence, not exiting. So threads are being **started** during the sequence. It is not a
  teardown-after-`main` race.
- **Not kore's threads.** On native both of kore's lanes are `Dispatchers.IO` (research D9, B-42), and
  kore starts or stops no thread of its own. CIO's dispatchers are the shared `Dispatchers.IOBridge`,
  and ktor-network's selector runs on the caller's dispatcher. What starts threads during the
  sequence is `Dispatchers.IO`'s elastic pool and the runtime's `Worker`s under it.
- **It predates B-60 to B-63.** `main` at `8aae43a` crashed 1 in 200 under the same signal. B-63's fix
  did not change it: 3 in 400 with the fix, in the acceptance run B-63 first wrote.
- **Under `gdb` it did not happen**: 0 in 600 runs. The timing change hides it.

## What is not known, and why

**The rate.** It was 3 in 400 in one run and **0 in 300** in the control of the next, same binary,
same conditions — on a build box whose load average was about 42 on 20 cores, from another project's
load test. A rate measured there says more about the box than about the binary. The cells that were
meant to locate it by timing are void for the same reason: their control found nothing.

**Whose it is.** A null field in the runtime's own worker startup points at the Kotlin/Native runtime,
or at how kotlinx.coroutines creates workers. Either way it is not code kore can change. **It is not
to be filed with JetBrains** — the owner's standing rule for findings made in sessions with an agent.
It stays recorded here.

## Reachability, and what bounds it

It needs threads started while the sequence runs, which the sample forces by draining 100 ms after
serving began. With kore's default five-second pre-drain, the drain comes long after startup's
thread creation has settled. That is a reason to expect it to be rare in production, **not a
measurement of it**. The crash ends the process with 139 instead of 0: Kubernetes records a crash,
and whatever the sequence had not finished is lost.

## Next steps, in order

1. Measure the rate on an idle machine, alternating `main` and the current build, with a control
   that reproduces first. Without that, nothing below can be judged.
2. A **release** binary, as the image ships, under the same signal — the debug binary is what was
   measured.
3. A minimal program with no Ktor and no kore: `Dispatchers.IO` work started in a burst right after
   startup, while another burst runs. If it crashes, the defect is below both libraries, and this
   item records that and closes as not kore's.

- AC: the rate is measured on an idle machine with a reproducing control, and the owner of the defect
  is established by the minimal program. Or a kore-side mitigation is shown to take the crash count to
  zero over a count the control makes meaningful.
- Anchors: `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/concurrent/KoreDispatchers.native.kt`
