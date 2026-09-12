---
id: feature-ordered-shutdown
title: Ordered shutdown
type: feature
status: active
owner: unassigned
involved_services:
  - kore-library
  - sample-service
client_entries: []
api:
  - endpoint-kore-admin
tags: [lifecycle, sigterm, drain]
---

# Ordered shutdown

> **Built.** The stage machine, the drain and the three release groups exist and are exercised on the
> JVM and on Kotlin/Native, and the ordering is asserted twice — by the property tests over the
> machine and by a `SIGTERM` sent to a real binary under load. The facts it rests on are verified and
> sourced in [research-architecture](../research/research-architecture.md) §1.1–§1.4 and §1.8–§1.10.
>
> **One scenario in §5 is still *target*** and says so where it stands: kore's own booblik
> participant does not exist ([B-15](../backlog/B-15-booblik-adapter.md)). The machine orders
> consumers before pools today; what is missing is the adapter, not the order.

## 1. Overview

When a kore service receives `SIGTERM`, it stops in a fixed, named order: it stops claiming to be
ready, waits long enough for that to reach whatever routes traffic, finishes the requests it had
already accepted, refuses anything new with a `503` that says not to reuse the connection, tells its
consumers and pools to stop — flushing before closing — and then exits, on its own, inside the grace
period it was given.

The order is the product. Everything in it is individually obvious and the combination is
individually got wrong: the portfolio's most complete service today cancels its workers and closes
its broker socket in a Ktor `ApplicationStopping` subscriber, which on Kotlin/Native runs **before**
in-flight requests have finished (research §1.1) — and on the JVM runs after, so the same source
means two different things on two targets with nothing saying so.

## 2. Business rules

Each rule is checkable, and each has a reason that is not "it seems tidier".

1. **Readiness goes false before anything else happens.** Not on a Ktor event: research §1.2 shows
   the only candidate event fires after the listening socket has stopped accepting, which is too late
   to be observed by a probe and too late to be useful.
2. **The announce stage then waits, and the wait is not a formality.** Research §1.10: during a
   rollout the control plane has already marked the terminating endpoint `ready: false` on its own,
   so what flipping readiness buys is the truth for anything that polls the pod directly. What
   actually stops traffic arriving after `SIGTERM` is the time it takes a rule change to reach every
   node. The default is derived from the Kubernetes floor — one readiness period times the failure
   threshold, plus propagation — and stated in §4 of
   [feature-health-probes](feature-health-probes.md), not chosen to look round.
3. **A request accepted before the signal gets a response.** Not a reset, not a truncated body.
4. **A request arriving after the announce stage is refused with `503` and `Connection: close` —
   and kore is what refuses it.** Ktor does not, and this was measured rather than assumed: with a
   grace period long enough to observe, CIO served 48 further requests on already-open connections
   after `SIGTERM` and refused none of them (research §1.13). "Stop accepting" stops new
   *connections*, not new *requests*. So the refusal is a plugin kore installs, gated on the sequence
   having begun; without it there is no refusal anywhere and the oracle's A3 has no subject.
   The header is a promise to the client that the connection is finished. It is deliberately *not* a
   promise that the server hangs up: on CIO the keep-alive decision is read from the **request's**
   `Connection` header (research §1.4), and kore will not assert what the engine does not do.
5. **Every stage has its own deadline, and one stage cannot spend another's.** A dependency check
   that hangs or a flush against a dead broker must not leave the drain with nothing.
6. **A participant that fails does not stop the sequence.** A shutdown that aborts halfway leaves
   open exactly the resources it exists to close. The failure is recorded; the remaining stages run.
7. **Consumers are flushed before they are closed, and closed before the pools are.** Research §1.8:
   the JVM booblik producer's `close()` completes every accumulated record *exceptionally* — closing
   without flushing drops up to a linger window of published events on every deployment, invisibly,
   because the records that vanish are the ones nothing was waiting on. The **native** producer of
   the same broker sends them instead. kore flushes explicitly on both rather than relying on either:
   relying on the implementation that happens to be right is how a difference between two of them
   goes unnoticed.
8. **The sequence runs exactly once**, whatever arrives: two signals, a signal and a programmatic
   stop, a signal during the sequence.
9. **The sum of the stage deadlines must fit inside the grace period**, and kore refuses at startup
   when it does not. A grace period shorter than the sequence is a `SIGKILL` in the middle of a
   drain, which in a log looks exactly like a crash.
10. **Undeclared, the grace period is assumed to be Kubernetes' 30 s — and the assumption is
    optimistic.** It is printed as an assumption wherever it is used, but rule 9's guard can only
    compare against the number it has. Where the real budget is smaller the guard passes a plan that
    will be killed: `docker stop` defaults to **10 s** (measured) against kore's own default deadlines
    of **29 s**. kore cannot discover the real budget on any platform it targets, so **outside
    Kubernetes, declare it** — [B-25](../backlog/B-25-undeclared-grace-period.md) has why the
    assumption stays rather than becoming a refusal.

## 3. The sequence

The specification. The names are public, because the property test of
[research-oracle](../research/research-oracle.md) §3 asserts over the recorded transitions and a
test can only assert over something that has a name.

```
SIGTERM / SIGINT
      │
      ▼
  signal      the handler sets a flag and wakes a parked coroutine. Nothing else.
      │       (research §1.3: Ktor's native handler runs runBlocking on the signal stack)
      ▼
  announce    readiness → false            deadline: none; then wait preDrainDelay
      │       /health/ready answers 503; /health/live still answers 200
      ▼
  drain       EmbeddedServer.stop(grace, timeout)          deadline: drainDeadline
      │       accept stops (new CONNECTIONS only); in-flight finishes;
      │       kore's own plugin answers 503 + Connection: close to anything new
      ▼
  release     ordered, three groups, each with its own deadline
      │         1. consumers   flush, then close
      │         2. pools       close
      │         3. telemetry   flush what can be flushed
      ▼
  exit        the process returns from main
```

**Why `release` is after `drain` and not a Ktor subscriber.** Research §1.1 consequence 3: on
Kotlin/Native `ApplicationStopping` fires before the drain, so a stage that must run after it
*cannot* be a Ktor subscriber at all. kore runs the release group itself, after `EmbeddedServer.stop`
has returned, on both platforms.

**Why `signal` is a stage with a name even though it does almost nothing.** It is where the two
platforms differ most and where the unsafe thing lives. Naming it is what lets the property test say
"the sequence began exactly once" about something a signal handler can deliver twice.

**Five stages in the story, seven in the machine, and the difference matters** *(found while
implementing, B-04)*. The diagram above reads as five, with `release` holding three groups. That is
the right story and it is ambiguous about the one thing the machine cannot be ambiguous about: **the
unit that carries a deadline**. `release` has three of them, so as a single stage it would need
either a deadline it does not have or three it cannot express — and property 1 of
[research-oracle](../research/research-oracle.md) §3.2 asserts the transcript is a *prefix of one
unambiguous list*. So `KoreStage` has seven entries, the three release groups among them, and the
five-stage grouping stays the human-facing one.

**The time bound is a promise about waiting, not about stopping** *(also B-04)*. A stage runs its
participants in a detached scope and, at the deadline, cancels that scope **without joining it**. So
a participant that ignores cancellation keeps running while the sequence moves on. The alternative —
structured concurrency, joining the children — makes rule 5 a lie the first time a participant blocks
in a way `withTimeout` cannot interrupt, which on Kotlin/Native is not hypothetical (research
Risk 3). A shutdown that overruns its grace period is killed mid-drain; a leaked coroutine in a
process that is exiting costs nothing.

**One stage spends its duration; the rest bound work** *(found by implementing, B-11)*. A stage with
nothing to do takes no time — waiting out a pool-closing deadline with no pools registered would be
absurd. The **announce** stage is the exception: its entire job is to wait, and the machine's obvious
behaviour deleted that wait silently. A test asked for seven seconds and got five microseconds. So
the duration carries what it *means*, and the announce stage is marked as time to spend rather than a
bound on work. It is the stage most likely to be optimised away by somebody who has not read why it
is there, and now the type says so.

**The drain deadline is not an upper bound; under load it is the shutdown time** *(measured, B-06)*.
CIO's `stop` waits for the connectors' jobs, and a keep-alive client keeps those alive, so the grace
period is spent in full whenever anything is still connected: a 20-second grace produced a
20.5-second shutdown with eight busy connections. kore's refusal changes what those clients *get*,
not how long the engine waits — so `drainDeadline` must be read as "how long shutdown takes under
load", and sized accordingly.

**What kore does not control, and says so.** metrik's plugin subscribes its own agent to
`ApplicationStopping` (research §1.6), so metrik stops inside the `drain` stage — after the drain on
the JVM, before it on Native. kore cannot reorder someone else's subscription without taking over the
plugin. The consequence is written down in
[feature-observability-wiring](feature-observability-wiring.md) §7 and carried as an upstream
proposal in [research-upstream-proposals](../research/research-upstream-proposals.md) §3, not
silently absorbed.

### What a consumer writes

`runUntilSignal` is the three steps that are kore's own — wait, run, release — and the registrations
are the service's:

```kotlin
server.start(wait = false)
startup.markStarted()
runBlocking {
    val run = runUntilSignal(deadlines) {
        announce(AnnounceNotReady(readiness))
        drain(EngineDrain(server, deadlines.drain, deadlines.drain + 5.seconds))
        pool(myPool)
    }
    println(run.transcript)
}
```

kore does **not** own `main` — [B-30](../backlog/B-30-entry-point-question.md), decided on the shape
of a real service whose entry point runs migrations and composes its own DI before any route exists.
What it owns is the stretch from the signal to the exit, and each of those three steps is one a
consumer gets wrong in a way that looks like it works: `start(wait = true)` never reaches the await, a
missing `releaseProcess()` costs latency nobody attributes to it, and a watch installed before the
server is serving catches a signal whose sequence has nothing to drain.

## 4. Code anchors

| Service | Code |
|---|---|
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/` — the stage machine and the recorded transitions |
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/ShutdownParticipant.kt` — the contract a consumer implements |
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/AnnounceNotReady.kt` — **built (B-09)**, the flip; the wait is the stage around it |
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/ReadinessGate.kt` — **built (B-09)**, where readiness lives |
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/signal/ShutdownSignalWatch.kt` — the contract, and why kore never calls `addShutdownHook` |
| kore-library | `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/signal/` — `signal()`, and a handler that writes one integer |
| kore-library | `kore-core/src/jvmMain/kotlin/io/github/youndie/kore/signal/` — the hook thread that must not return until the sequence is done |
| kore-library | `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/` — the wrapper that calls `EmbeddedServer.stop` itself |
| kore-library | `kore-booblik/` — flush-then-close; **not built yet**, its target set is [B-36](../backlog/B-36-booblik-adapter-targets.md) |
| sample-service | `samples/oracle/` — the load driver and the assertions |

## 5. Scenarios (BDD)

**Six of nine are automated as of B-12.** Four go through the oracle against a real container taking
a real `SIGTERM`; one is the stage machine's own. The three that remain need a participant that hangs or
throws, or a booblik producer — none of which the sample has yet. A
scenario gains its line when a test covers **all** of it; the absence is the honest signal and
`bdd_report` counts it as manual.

### Scenario: a request in flight when the signal arrives is finished
* **Given:** the service is serving and `N` requests are in flight on the slow route
* **When:** the process receives `SIGTERM`
* **Then:** every one of those `N` requests receives a complete response
* **And:** none of them receives `500`
* **And:** the assertion is made from the client's record, not from the server's log
* **Automated:** `negative-control.sh`

### Scenario: readiness falls before the drain begins
* **Given:** the service is serving and a poller is calling `GET /health/ready` every 100 ms
* **When:** the process receives `SIGTERM`
* **Then:** `/health/ready` answers `503` strictly before the first request is refused
* **And:** the interval between that `503` and the first refusal is at least the configured
  pre-drain wait
* **Automated:** `negative-control.sh`

### Scenario: a request arriving during the drain is refused, and says so
* **Given:** the process has entered the drain stage
* **When:** a new request arrives on an already-open keep-alive connection
* **Then:** the response is `503`
* **And:** it carries `Connection: close`
* **And:** it is **not** asserted that the server closed the socket — research D6
* **Automated:** `negative-control.sh`

### Scenario: a participant that hangs does not consume the drain
* **Given:** a registered consumer whose flush never returns
* **When:** the sequence reaches the release stage
* **Then:** that participant's group ends at its own deadline
* **And:** the remaining groups still run
* **And:** the process still exits inside the grace period
* **Automated:** `ShutdownSequenceTest.property 3 - a participant over its deadline does not extend
  the stage`, and `property 5 holds even when a participant ignores cancellation` for the harder
  case — a participant that cannot be cancelled at all. The last clause is the oracle's: `negative
  control` measures the exit, because a unit test with a virtual clock cannot observe a process

### Scenario: a dependency check blocking a thread does not delay the sequence
* **Given:** a health check that occupies its thread without suspending, and a shutdown sequence
* **When:** the signal arrives while that check is blocked
* **Then:** the sequence completes within its own deadlines rather than behind the check
* **And:** this is the scenario that fails when kore's background work shares one lane — research D9
* **Automated:** `BlockingCheckTest` (jvm and linuxX64), with `SharedLaneControlTest` (linuxX64) as
  its positive control: on one shared lane the same check delays the sequence by its full duration,
  which is what makes the first test's green mean something

### Scenario: a participant that throws does not abort the sequence
* **Given:** a registered pool whose `close()` throws
* **When:** the sequence reaches the release stage
* **Then:** the failure is recorded
* **And:** the telemetry group still runs and the process still exits `0`
* **Automated:** `ShutdownSequenceTest.property 4 - a participant that throws does not stop the
  sequence`, and `a stage records every failure and not only the first` — because "the failure is
  recorded" is a weaker claim than what the machine promises

### Scenario: a JVM producer's accumulated records are flushed before it is closed
* **Given:** a JVM booblik producer holding records inside its linger window — records whose only
  route to the socket is a coroutine the shutdown is about to cancel
* **When:** the sequence reaches the release stage
* **Then:** those records are sent *and acknowledged* before the producer's scope and connection go
* **And:** no record is completed with `ConnectionClosedException` as a result of the shutdown
* **Automated:** `BooblikParticipantTest` (jvm and linuxX64) — five cases over kore's half of this,
  which is the **order**: the flush happens first, the close never comes first, a flush that never
  returns is bounded and the producer is still closed, and a flush that throws still closes while the
  failure reaches the stage machine.
* **Automated end to end:** `samples/oracle` `brokerFlush` against `ghcr.io/youndie/booblik:latest` —
  three arms, five runs, **1 of 51** records read back when the producer is closed and torn down in
  the same breath and **51 of 51** through this participant
  ([B-45](../backlog/B-45-booblik-against-a-real-broker.md),
  [write-up](../research/measurements-2026-09-12/broker-flush.md)). It is a sample rather than a
  check: it needs docker and a pullable image, and a red one would report on another repository.
* **What the unit tests do not assert, and nobody should read them as asserting:** that a flush puts
  records on a socket. That is booblik's promise, and the run above is what holds it to it. It also
  corrected the premise this scenario used to state: `close()` does not fail the batch — it sends it
  without waiting, and a teardown that does not wait either is what loses it (research §1.8)

### Scenario: two signals run the sequence once
* **Given:** the sequence has begun
* **When:** a second `SIGTERM` arrives
* **Then:** the recorded transitions contain each stage exactly once
* **Automated:** `ShutdownSequenceTest`

> The only scenario in this document that is automated today, and deliberately the only one. The
> other two the stage machine touches — a participant that hangs, a participant that throws — both
> end in "and the process still exits", which no test of a machine can observe. They stay unmarked
> until the oracle of [research-oracle](../research/research-oracle.md) §2 can run them.

### Scenario: the two platforms agree
* **Given:** the JVM sample and the native sample, same source, same scenario
* **When:** each is put through the run above
* **Then:** every assertion has the same outcome on both
* **And:** a divergence fails the run rather than being reported as a platform difference
* **Automated:** `negative-control.sh`

### Scenario: a sequence that cannot fit is refused at startup
* **Given:** stage deadlines summing to more than the grace period kore was told about
* **When:** the process starts
* **Then:** it refuses to start and names the two numbers
* **Automated:** `ShutdownPlanTest`

## 6. Out of scope

* **Multiple replicas.** Whether a rolling deploy drops a request depends on readiness periods,
  endpoint propagation and surge settings — the chart's business, not kore's.
* **`SIGKILL`, OOM kills, and a node going away.** kore's promise is about `SIGTERM`.
* **Restarting anything.** kore stops a process; it does not manage one.
* **Draining at the connection level.** kore refuses at the request level. Connection-level draining
  needs engine cooperation that CIO does not offer (research §1.4).

## 7. Quirks

* **`Connection: close` does not close the connection on CIO.** Research §1.4. The client is expected
  to honour it; the socket goes when the client closes it, when CIO's 45-second idle timeout expires,
  or when the grace period ends and the job is cancelled.
* **`timeoutMillis` is an absolute budget, not an extra one.** CIO's hard-kill window is
  `timeoutMillis - gracePeriodMillis` (research §1.2). Configured with `timeout <= grace`, the engine
  cancels and then does not wait for the cancellation to take effect. kore validates the pair at
  startup rather than letting it be discovered.
* **On Kotlin/Native, whoever registers a shutdown hook last wins the only slot there is.** kore
  registers after `EmbeddedServer.start` and therefore wins today. A library added later that also
  calls `addShutdownHook` would take it back silently. Risk 2 of the research; it is why the oracle
  asserts the *sequence* and not merely the exit code.
* **The refusal must not refuse the liveness probe.** A `503` from `/health/live` is a failed
  liveness probe, and enough of them restart the pod **in the middle of the shutdown it is
  reporting** — turning the orderly stop into the abrupt one this feature exists to prevent. Startup,
  liveness, `/health` and `/version` keep answering; readiness is exempt for a different reason, that
  it is *supposed* to fail and says which check did.
* **On the JVM the shutdown hook thread is the shutdown, and returning from it ends the process.**
  So kore's hook waits for the sequence to say it is finished — bounded, so forgetting that call
  costs latency rather than a process that will not exit. On Native the handler returns immediately
  and `main` ending is what stops the process. Same promise, opposite mechanics.
* **The announce stage has no deadline of its own.** Flipping a flag cannot fail and cannot hang. The
  wait that follows it is a duration, not a deadline, and that distinction is why it survives a
  reading of the code by somebody looking for things to delete.
