---
id: B-08
title: "Signal handling: a handler that only sets a flag"
status: done
priority: P0
size: M
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-04]
---

# B-08 — Signal handling: a handler that only sets a flag

`sigaction` on the POSIX targets, `Runtime.addShutdownHook` on the JVM, and in both cases a handler
that does nothing but set an atomic and wake a coroutine that is already parked.

- **The decision and its reason.** Research §1.3: Ktor's native handler runs arbitrary Kotlin —
  allocation, locks, `runBlocking` — on the signal-handler stack, which is not async-signal-safe.
  `sigaction`, `sigemptyset` and `sigfillset` are present on `linux_x64` and `linux_arm64`, verified
  in the platform klibs, so the handler can be installed properly rather than through ANSI `signal()`.
- **Rejected:** `EmbeddedServer.addShutdownHook`. On Native it is a single global slot and the last
  registration wins — either kore replaces Ktor's hook or Ktor replaces kore's, decided by an
  ordering nobody writes down (research D3).
- Does **not** cover: `SIGKILL` or OOM. kore's promise is about `SIGTERM`.
- Watch for: kore must register **after** `EmbeddedServer.start` returns, and a test must show that
  kore's sequence is what ran — Risk 2.

- AC: on both platforms, a `SIGTERM` to a running sample begins kore's sequence; two signals run it
  once; the handler itself allocates nothing.
- Anchors: `kore-core/src/posixMain/kotlin/io/github/youndie/kore/signal/`, `kore-core/src/jvmMain/`

## Iteration 1 — 2026-09-12

**Done.** `ShutdownSignalWatch` in `commonMain`, with the two halves that genuinely cannot be shared
in `nativeMain` and `jvmMain`. Seven tests, green on both platforms, all three mutations killed.

| Checked | How |
|---|---|
| a real `SIGTERM` starts the sequence | `raise(SIGTERM)` **in the test process** on `linuxX64`. There is no way to check this with a double: if the handler is not installed the test process dies |
| two signals do not kill the process mid-sequence | raised twice; the suite stays up. This is the `signal()` semantics question asserted rather than cited |
| the first signal names the shutdown | `SIGTERM` then `SIGINT`; the answer is `SIGTERM` |
| the JVM hook waits, and gives up on its own | drives `onRuntimeShutdown` — the hook thread's actual body, not a double — and times it |

**Mutations, all killed:**

| Mutation | Result |
|---|---|
| plain store instead of `compareAndSet` | 1 of 16 red — a later signal rewrote the answer |
| the JVM hook does not wait for `releaseProcess` | 1 of 16 red |
| do not install the handler at all | **14 of 16 completed** — the test process was killed by the signal, mid-test, exactly as the KDoc says |

The third is the interesting one: the documented failure mode is "the process dies", and it does. A
claim in a comment that can be made to happen on demand is worth more than a citation.

**Two corrections to documents, both from implementing:**

1. **`signal()`, not `sigaction()`.** Research §1.3 said kore would install `sigaction` because the
   Linux platform klibs expose it — which they do. They also expose a `struct sigaction` whose shape
   differs from Darwin's (`__sigaction_handler` against `__sigaction_u`), and all three native targets
   share one source set, so `sigaction` would mean two implementations of a handler that writes one
   integer. **What separates kore from Ktor is what the handler does, not what installs it**, and
   that claim survives intact. §1.3 amended at the point of divergence.
2. **`nativeMain`, not `posixMain`.** `applyDefaultHierarchyTemplate()` creates no `posixMain` — that
   was Ktor's own intermediate source set, read in their jar and copied into our documents as though
   it were a Kotlin convention. Four documents named a directory that could not exist.

**A gotcha now in `CLAUDE.md`:** `runTest`'s clock is virtual, so `withTimeout` inside it expires
without any wall time passing. The JVM test that waits for a real thread failed against a mechanism
that works, until it became `runBlocking`.

**Deliberately not done:** wiring this to a server. Who calls `awaitSignal` and what happens next is
[B-10](B-10-drain-stage.md); this item built the mechanism and stopped. In particular `releaseProcess`
has no caller yet, which is why its timeout is bounded.
