---
id: B-63
title: "A SIGTERM before kore's handler meets Ktor's native handler, which hangs the process"
status: done
priority: P2
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-63 — A SIGTERM before kore's handler meets Ktor's native handler, which hangs the process

**Filed as a segfault, and the segfault turned out to be another defect.** The item was opened
because about one start in 200, signalled the moment `/health` answered, died with exit 139. Its
hypothesis was the window between `EmbeddedServer.start` and kore's handler, where Ktor's is armed.
Testing the hypothesis found a real defect in that window — **a hang, not a crash** — and it is
fixed here. The segfault survived the fix at the same rate, and the evidence places it elsewhere: it
is [B-64](B-64-new-worker-thread-segfaults-at-birth.md). The title was changed; the filename was
kept so links hold.

## The window, established

Ktor's native `start` installs its handler on `ApplicationStarting`. The handler's body is `stop()`,
which runs `runBlocking`, and it runs on the signal stack (research §1.3, consequence 2). kore's
handler replaced it only inside `runUntilSignal`, after the server was already answering.

**Widened on purpose:** a throwaway build pausing one second after `startForKore()`, signalled as soon
as `/health` answered — **30 of 30 hung** and did not exit within five seconds. `/proc` showed the
main thread with `SIGTERM` in its blocked mask, which is the kernel's sign that a thread is inside a
handler for it. Run as a child of `gdb`, the main thread's stack read
`platformAddShutdownHook$1.invoke` → `EmbeddedServer.stop` → `CIOApplicationEngine.stop` →
`runBlocking` → `Worker.park`. Every coroutine worker was blocked on one runtime mutex in
`Worker.executeAfter`, held by the code the signal had interrupted.

## Fix

`startForKore()`, on Kotlin/Native, installs kore's handler — the one that only sets a flag —
**before** `start`, **again at `ApplicationStarted`**, and **once more** when `start` returns.
`ApplicationStarted` is raised after Ktor's handler goes in and before `engine.start`, so from before
the first byte is served, a signal is recorded for `runUntilSignal`'s watch and acted on there. On the
JVM it is a no-op: the watch there is a shutdown hook, and a second one would be a second to release.

**What remains:** module loading, between `ApplicationStarting` and `ApplicationStarted`. Nothing has
been served yet, and a signal there still meets Ktor's handler. Ktor's side is research-upstream-
proposals §1.2, now with this measurement.

**The price, stated where it is paid:** a native service that calls `startForKore()` has to go on to
`runUntilSignal`, or install the watch itself. kore's handler only records the signal. The KDoc and
the feature document say so.

- **Rejected: blocking `SIGTERM` and `SIGINT` around `start`.** Threads created before it, the GC's
  among them, do not block the signal, and the kernel may deliver a process-directed signal to any
  of them — to Ktor's handler, on that thread.

## Verified

- **`StartForKoreSignalTest`** (linuxX64): `raise(SIGTERM)` straight after `startForKore()`, and
  kore's watch reports it within two seconds. **Mutation:** removing the three installs made it red in
  one second — `Expected <SIGTERM>, actual <null>` — with no hang, because `raise` runs the handler on
  a thread that holds nothing.
- **End to end, widened window:** the same throwaway build on the fixed code — **30 of 30 exited 0
  with a shutdown transcript**, against 30 of 30 hung before.
- `./gradlew :kore-ktor:jvmTest :kore-ktor:linuxX64Test` green; `KtorShutdownHookJvmTest` unchanged.

- AC, as corrected: a signal in the window between `start` and `runUntilSignal` is recorded for kore
  and never runs Ktor's handler — established by a stack, not inferred; widened, 0 hangs in 30 where
  there were 30. **Met.** The AC as first written — "400 early-signalled starts, no exit 139" — was
  about B-64's defect and is carried there.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/StartForKore.kt`,
  `kore-ktor/src/nativeMain/kotlin/io/github/youndie/kore/ktor/StartForKore.native.kt`,
  `kore-ktor/src/nativeTest/kotlin/io/github/youndie/kore/ktor/StartForKoreSignalTest.kt`
