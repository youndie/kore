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

## Investigation, 2026-09-27 — the path is established; the owner and the rate are not

**Who starts the doomed thread.** An `LD_PRELOAD` that records, for every `pthread_create`, the stack of
the code creating the thread showed the same path in **all 7** crashes it caught:
`io.ktor.network.selector.SelectorHelper.selectionLoop` → `fillHandlersOrClose` → `EventInfo.complete`
→ `CancellableContinuationImpl.resumeWith` → `Dispatchers.IO` → `MultiWorkerDispatcher.dispatch` →
`Worker.start`. So ktor-network's selector is closing and resuming everyone waiting on it. The selector
loop itself runs on an IO worker (`LimitedDispatcher.Worker.run`). The resumption needs a worker that
does not exist yet, and the new thread dies on its first instructions.

**Why the new thread dies, as far as the runtime source says.** In Kotlin/Native 2.4.20
`workerRoutine` calls `Kotlin_initRuntimeIfNeeded()`, then builds a `ThreadStateGuard` from
`worker->memoryState()` — the field at `+0xf8`, which `WorkerInit` sets. It is null there. Either the
thread's runtime initialisation was skipped, or the `Worker` it was handed is no longer the one
initialised. Neither is code kore or Ktor controls.

**The pool it grows.** On native, `Dispatchers.IO` is a `limitedParallelism(64)` view over a
`newFixedThreadPoolContext(2048)` whose workers are started on demand. A worker is born whenever demand
exceeds the workers alive, up to 64 active.

**A program with neither Ktor nor kore did not reproduce it** — with the control reproducing beside it:

| alternated in one run, same reporter | runs | exit 139 |
|---|---|---|
| kore's sample, early `SIGTERM` (the control) | 600 | 7 (reporter held the exit; 7 reports) |
| minimal program, burst from `main` | 1 800 | 0 |
| kore's sample again (control) | 600 | 3 |
| minimal program, burst from **inside an IO worker** | 1 800 | 0 |

Plus 2 000 debug and 2 000 release runs of the first minimal program on their own: 0. So starting new IO
workers in a burst, even from a worker thread, is not enough. The sample supplies a further condition
this has not isolated: a selector closing, sockets, the signal, or load. Until it is isolated, "not
kore's" is well supported (runtime code, no kore thread) and "whose" is not established.

**The rate is still unmeasured.** The box stayed at load 25–48 from other projects' load tests
throughout. The control's 3 to 7 in 600 is a count on that box, not a rate.

**One mitigation kore could own, and why it is not taken yet.** Births during the sequence can be
avoided by starting IO workers before serving — occupying the 64 slots once at startup. On native,
resident memory follows the **thread count** (B-55: the per-thread page cache dominates), so this buys
a rare crash at shutdown with memory every pod pays all the time. It would need its own measurement on
both sides before it is a decision.

## Next steps, in order

1. Measure the rate on an idle machine, with a control that reproduces first, and on a **release**
   sample binary — the crashes above are all the debug one.
2. Narrow the minimal program towards the sample: a ktor-network server socket with waiting accepts,
   closed while new IO workers are needed. That would put the trigger in ktor-network plus the runtime,
   with no kore or CIO.
3. Only if the rate is material: measure the pre-started-workers mitigation — crashes against resident
   memory — before choosing it.

- AC: the rate is measured on an idle machine with a reproducing control, and the owner of the defect
  is established by the minimal program. Or a kore-side mitigation is shown to take the crash count to
  zero over a count the control makes meaningful.
- Anchors: `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/concurrent/KoreDispatchers.native.kt`
