---
id: B-08
title: "Signal handling: a handler that only sets a flag"
status: open
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
