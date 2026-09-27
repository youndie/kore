---
id: B-64
title: "A SIGTERM that lands on a newborn worker thread kills it, because the handler was a Kotlin bridge"
status: done
priority: P3
size: M
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-64 — A SIGTERM that lands on a newborn worker thread kills it, because the handler was a Kotlin bridge

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

## Step 2, 2026-09-27 — the mechanism, established without kore

A throwaway program, never committed, with modes cut from the sample one piece at a time. Each run
was driven like the sample (poll `/health`, then `SIGTERM` at once) and alternated with a control in
the same run:

| mode | what it has | exit 139 |
|---|---|---|
| sample (control, several runs) | everything | 4–9 in 600 each time |
| `net` | ktor-network only, the selector closed under waiters | 0 / 600 |
| `cio`, `cio-suspend` | bare CIO, stopped by a timer (`stop` / `stopSuspend`) | 0 / 600 each |
| `kore-min` | bare CIO + kore's announce and `EngineDrain` | 9 / 600 |
| `sig` | bare CIO + a hand-written `staticCFunction` handler, polled on IO — **no kore** | 5–9 / 600 |
| `sig-nopoll` | the same, polled by a blocking sleep loop on main | 3 / 600 |
| `poll-nosig` | the IO poll, no handler, no signal | 0 / 600 |

**So the trigger is a signal, and kore is not needed.** A `LD_PRELOAD` then recorded which thread
received `SIGTERM` in every run:

| receiver | runs | crashed |
|---|---|---|
| the main thread | 1 152 | 0 |
| another thread | 48 | 18 — **and in 18 of 18 the receiver was the thread that crashed** |

**Mechanism.** The client's connection closes at the moment of the signal, so waking its reader starts
a new `Dispatchers.IO` worker at that same instant. The kernel may deliver the process-directed
`SIGTERM` to that newborn thread. A `staticCFunction` handler is a C-to-Kotlin bridge, and a bridge
initialises the runtime on a thread that has none. So the handler initialises it there, **before**
`workerRoutine` does. `workerRoutine` then finds the runtime valid, skips its own initialisation,
never gives its `Worker` a memory state, and dies on the next line. Research §1.3 consequence 2 said a
signal handler must do nothing but set a flag. On Kotlin/Native a handler written in Kotlin cannot keep
that promise, however little its body does. Ktor's native handler is a `staticCFunction` too.

**The fix, measured in the same program:** the handler in **C**, through cinterop with inline code —
`volatile sig_atomic_t`, `signal()`, no Kotlin on the receiving thread.

| handler, alternated | runs | receiver not main | exit 139 |
|---|---|---|---|
| Kotlin `staticCFunction` (control) | 1 000 | 22 | **16** |
| C via cinterop | 1 000 | 16 | **0** |

## Fix

**kore's handler is C on the Linux targets**: `kore-core/src/nativeInterop/cinterop/koreSignal.def`,
inline, for `linuxX64` and `linuxArm64`. `kore_on_signal` does a lock-free compare-and-set on an
`int`, so the first signal still names the shutdown, and a second `SIGTERM` still finds the handler
installed. `installShutdownSignalWatch` installs it through a per-platform `installSignalHandlers`;
the watch polls `raisedSignal()`; `startForKore()` (B-63) installs it through the same call. The JVM
is untouched.

**macOS keeps the Kotlin handler, and the defect with it — decided by the owner.** cinterop for an Apple
target needs the macOS SDK. On the Linux host kore is released from, `cinteropKoreSignalMacosArm64`
was **SKIPPED**, and it took `compileKotlinMacosArm64` with it, inside a **green** `./gradlew build`.
The local publication had no `macosarm64` artefact at all. A per-target compile run had looked green
for macOS too: that was the skip. The alternative was publishing from a macOS runner. macOS is a
development target here, so the limitation stays on it, named in `SignalHandler.macos.kt`.

- **Rejected: pre-starting the IO workers** so none is born during a shutdown. It would cost resident
  memory in every pod (B-55), and it hid the defect rather than removing it: any birth that coincides
  with a signal would still be exposed.
- **Rejected: blocking the signals everywhere and waiting in one thread.** The runtime's GC threads
  exist before `main` and cannot be made to block them.

## Verified

- **`the installed handler for both signals is kore's C function`** (`SignalHandlerLinuxTest`, on
  linuxX64 and, in CI, linuxArm64). **Mutation:** a `staticCFunction` put back over the C handler
  turned it red with its own message — and, before the test moved to `linuxTest`, the three signal
  tests beside it too.
- `./gradlew build` on the Linux host: green, with `compileKotlinMacosArm64` **executed** for
  kore-core, kore-ktor and kore-booblik, not skipped. The local publication of kore-core carries
  `-cinterop-koreSignal.klib` beside the linuxx64 and linuxarm64 klibs, listed in the module metadata,
  and a macosarm64 klib again.
- **End to end**, the sample's native debug binary, `main` (0.1.9) against this change, alternated,
  1 000 each, on a quiet box (load 1–4), with the receiving thread recorded:

| build | runs | `SIGTERM` to a thread other than main | exit 139 |
|---|---|---|---|
| `main`, Kotlin handler | 1 000 | 26 | **8**, the receiver being the crasher in 8 of 8 |
| this change, C handler | 1 000 | 19 | **0** |

The after binary was built before the handler moved to `linuxMain`. The Linux path is the same C code
through the same call; only macOS changed.

- AC: the rate is measured on an idle machine with a reproducing control, and the owner of the defect
  is established by the minimal program. Or a kore-side mitigation is shown to take the crash count to
  zero over a count the control makes meaningful. **Met, both halves.** The mechanism is established
  without kore: a Kotlin handler's bridge on a newborn worker, the receiver being the crasher in every
  crash. The fix takes 8 in 1 000 to 0 in 1 000 beside its control, on an idle box.
- Anchors: `kore-core/src/nativeInterop/cinterop/koreSignal.def`,
  `kore-core/src/linuxMain/kotlin/io/github/youndie/kore/signal/SignalHandler.linux.kt`,
  `kore-core/src/macosMain/kotlin/io/github/youndie/kore/signal/SignalHandler.macos.kt`,
  `kore-core/src/linuxTest/kotlin/io/github/youndie/kore/signal/SignalHandlerLinuxTest.kt`,
  `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/signal/ShutdownSignalWatch.native.kt`,
  `kore-core/src/nativeTest/kotlin/io/github/youndie/kore/signal/ShutdownSignalWatchNativeTest.kt` (the
  behaviour, on every native target)
