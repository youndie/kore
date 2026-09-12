---
id: B-46
title: "Collapse signal-to-sequence into one call"
status: wip
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
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/`,
  `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/KoreWiring.kt`
