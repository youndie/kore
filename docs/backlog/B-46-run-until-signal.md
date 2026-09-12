---
id: B-46
title: "Collapse signal-to-sequence into one call"
status: done
priority: P2
size: S
stage: m5-wiring
epic: feature-ordered-shutdown
blocked_by: []
---

# B-46 — Collapse signal-to-sequence into one call

Raised by [B-30](B-30-entry-point-question.md), which decided kore does **not** own the entry point
and then had to say what that costs: about eight lines of ceremony in every consumer, around the
registrations that are genuinely the service's own.

```kotlin
server.start(wait = false)
startup.markStarted()
val watch = installShutdownSignalWatch()
runBlocking {
    watch.awaitSignal()
    val transcript = shutdownSequence(deadlines) { /* the service's registrations */ }.run()
    println(transcript.describe())
    watch.releaseProcess()
}
```

Everything there except the registrations is kore's own: waiting for a signal, running the sequence,
releasing the process. A consumer that gets one of those steps wrong — `start(wait = true)`, or
forgetting `releaseProcess` — gets a shutdown that looks like it works.

- **The decision and its reason.** Owning `main` was rejected on evidence (B-30). Owning the *stretch
  between the signal and the exit* was not: it is exactly the thing this library promises, and it is
  the part a consumer has no reason to write twice.
- **Rejected:** making it the only path. The pieces stay public — a service with its own signal
  handling, or one that runs the sequence for a reason other than a signal, must keep being able to
  drive the machine directly. That is what makes the property test possible at all.
- Does **not** cover: starting the server. That is the entry point, and B-30 decided it is not kore's.

- AC: the sample's shutdown half is the call plus its registrations and nothing else; the pieces it
  replaces are still public and still used directly by at least one test; the transcript is still
  reachable by a caller that wants it.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/RunUntilSignal.kt`,
  `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/KoreWiring.kt`

## Findings

* **It belongs in `kore-core`, not `kore-ktor`** — the anchors said otherwise and were wrong. The call
  needs a signal watch and a stage machine and nothing else; `EngineDrain` is a *registration the
  caller passes*, not a dependency of the call. Putting it in the Ktor module would have made a
  service with no HTTP server resolve one to get an ordered shutdown.
* **`suspend`, not blocking.** A library that blocks a thread it was not given is choosing for a
  process it does not own — and the caller needs `runBlocking` anyway, because on Kotlin/Native `main`
  does. It is also what keeps the *order* testable: the four calls are driven on a virtual clock,
  which a `runBlocking` inside would have made impossible.
* **Verified through the oracle, not only by tests.** The sample is the oracle's subject, so changing
  its shutdown path is a change to what the oracle measures. A1–A6 pass on both images after the move
  — `exit 143 after 15.5 s` on the JVM, `exit 0 after 17.5 s` on native.
* **A mutation that did not compile proved nothing, and said so.** Removing the `awaitSignal` call
  left `signal` unresolved; the failures printed afterwards were the *previous* mutation's, still in
  the result files. Re-run with the results deleted first, the honest mutation — running the sequence
  before awaiting — was killed by `a participant ran before the signal arrived`.
