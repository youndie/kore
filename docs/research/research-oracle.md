---
id: research-oracle
title: kore — the acceptance oracle and the numbers, written before the code
type: research
status: active
date: 2026-09-11
---

# Research: the oracle and the numbers

This document is the acceptance instrument, written **before** the implementation on purpose. A
library whose entire claim is about an *order* is a library where the test can be written to match
whatever was built: reorder two stages, and a suite that checks "everything closed and the process
exited" stays green. Writing the oracle first means the implementation is answerable to something it
did not shape.

It has three parts and they are not interchangeable. §2 is an end-to-end experiment on a real
binary under a real signal — it is the only thing that can observe [§1.1 of
research-architecture](research-architecture.md), because the platform difference it describes does
not exist inside a unit test. §3 is a property test over the stage machine — it is the only thing
that covers orderings nobody thought to script. §4 is the measurement plan, which answers "did this
cost anything" and is not a gate.

[research-architecture](research-architecture.md) is the companion: every claim this document tests
is stated and sourced there.

---

## 1. The negative control comes first

**Before kore exists, the oracle of §2 is run once against a service built the ordinary way** — the
JVM sample and the native sample, each wired the way a Ktor example and the first consumer wire
things: one `ApplicationStopping` subscriber, one `/health` route, `embeddedServer(...).start(wait = true)`.

**And it has to be run at two grace periods, not one** *(learned by running it, B-06)*. Ktor's
default `shutdownGracePeriod` is **one second**. At that setting every request slower than a second
is cut off on both platforms, for a reason that has nothing to do with ordering — and a run at the
default alone produces a confident, wrong conclusion about §1.1. It nearly did. So: once at the
default, to record what an unconfigured service actually does, and once at a grace period long enough
for the ordering question to be visible.

The reason is not ceremony. Every fact in research-architecture §1.1 says this configuration should
break under load on Kotlin/Native and survive on the JVM. If it does not break, one of three things
is true — the fact is wrong, the load is not load, or the harness cannot see the failure — and each
of those is cheaper to learn in week one than after five features have been built on the premise.

The control is a milestone gate of its own ([B-03](../backlog/B-03-negative-control.md)) and its
result is written into research-architecture §1.1 as a correction if it contradicts what is written
there now. A negative result is a finding; a negative result that is quietly dropped is how a
document starts lying.

**Run on 2026-09-11. The premise holds, in its corrected form** —
[the matrix](measurements-2026-09-11/negative-control.md). Two things it settled and one it nearly
got wrong:

* with a stop subscriber that closes something the in-flight request needs, the JVM is fine and
  Kotlin/Native returns **48 responses in 5xx on every run**. §1.1's ordering, producing a real
  defect;
* at Ktor's **default** grace period of 1000 ms, both platforms drop in-flight work for a reason that
  has nothing to do with ordering — which alone justifies a library that sets those numbers;
* and the first version of the control had a subscriber that closed **nothing**. Twelve consistent
  runs that demonstrated nothing about §1.1, and they would have read as "the ordering makes no
  difference in practice". A control has to be able to fail for the reason it is testing.

---

## 2. The end-to-end oracle: `kill -TERM` under load

### 2.1 What is under test

One service binary, built from the sample in
[services/sample-service](../services/sample-service.md), in two variants that run the **same
scenario**: `jvm` and `linuxX64`. A divergence between them is a failure of the run, not a curiosity
— the promise kore makes is that the two behave identically, and the whole of
research-architecture §1.1 is the reason that promise is not free.

The service under test holds, at minimum:

- a route whose handler takes a configurable time to answer, long enough that requests are certainly
  in flight when the signal arrives;
- one dependency with a pool, so the release stage has something real to close;
- one shutdown participant standing in for a consumer, so the flush-then-close of
  research-architecture §1.8 is exercised.

### 2.2 The procedure

1. Start the binary in a container with a known `terminationGracePeriodSeconds` equivalent, and wait
   for `GET /health/ready` to answer `200`.
2. Drive a steady, closed-loop load against the slow route — a fixed number of connections, keep-alive
   on, each recording the status, the response headers, and whether the connection was reused.
3. Once the load is in steady state, send `SIGTERM` to PID 1 and start a monotonic clock.
4. Keep the load running until every connection has either received a final response or errored, then
   stop.
5. Wait for the process to exit and record the exit code and the wall-clock time from the signal.

Everything the run asserts is derived from the client's own record plus the process's exit. Nothing
is asserted from the server's log, because a log line is written by the code under test and an
oracle that trusts it can be satisfied by a comment.

### 2.3 What the run asserts

| # | Assertion | Why it is the interesting one |
|---|---|---|
| A1 | **Every request that had been accepted before the signal received a response.** No connection ends with a reset, a truncated body, or a read timeout. | This is the whole claim. It is also the one that fails on Kotlin/Native today for the reason in research-architecture §1.1. |
| A2 | **Every response is either a normal status or `503`.** No `500`. | A `500` means a handler ran against something that had already been closed — the release stage overtaking the drain. A `503` is a refusal kore *chose*; a `500` is one it suffered. |
| A3 | **Every `503` carries `Connection: close`.** | So a client that honours the header does not put the connection back in its pool. Deliberately *not* "the server closed the socket" — see research-architecture D6. **Against a service without kore this has no subject and reports NOT_APPLICABLE**: Ktor refuses nothing while it drains (research §1.13), which is itself the finding. |
| A4 | **`GET /health/ready` answered `503` strictly before the first request was refused**, and strictly before the first `503` of any kind. | Readiness falls before the drain begins. This is the ordering claim, checked from outside the process. |
| A5 | **The interval between the readiness signal and the first refusal is at least the configured pre-drain wait.** | The wait is the stage that does the work (research-architecture §1.10); without this assertion it can be deleted and everything else still passes. |
| A6 | **The process exited on its own, inside the grace period, without being `SIGKILL`ed.** | A sequence that is correct and slower than its budget is a sequence that never runs to the end in production. |
| A7 | **The `jvm` and `linuxX64` runs agree on A1–A6.** | Stated as an assertion so a platform-specific regression is a red run rather than a difference somebody notices later. |

**A6 used to say "with code 0" and that was wrong** — corrected on 2026-09-11 against the two
containers of B-05, before anything was built on it. A clean `SIGTERM` shutdown produces a
**different exit code on each platform**: the native binary returns from `main` and exits **0**,
while the JVM runs its shutdown hooks and then exits **143** (`128 + SIGTERM`), which is the normal
and correct outcome there. Asserting `0` would have failed every JVM run for being right.

So the assertion is about *how* the process ended, not about a number: it ended itself, inside the
budget, and was not killed. `137` (`128 + SIGKILL`) is the failure this is looking for. The exit code
is still recorded in the run's output, because a change in it is worth seeing even when it is not a
failure.

### 2.4 What the run deliberately does not assert

- **That the server closed the connection.** research-architecture §1.4: CIO decides keep-alive from
  the request's `Connection` header, so this would pass because of the 45-second idle timeout or
  because of the grace-period cancellation — in both cases for a reason unrelated to kore.
- **Any absolute timing beyond A5 and A6.** Wall-clock numbers belong in §4, where they are measured
  as a comparison rather than asserted against a constant that describes the runner.
- **A particular exit code.** See the correction above: the two platforms disagree about what a clean
  `SIGTERM` shutdown returns, and both are right.
- **Anything about ordering *inside* the release stage.** That is §3's job; the oracle cannot see it
  from outside the process, and an oracle that claims what it cannot observe is the failure mode this
  document exists to avoid.

### 2.5 How it can pass for the wrong reason, and what stops that

An end-to-end oracle that visits nothing is green. Three specific ways this one could, and the guard
for each:

| Way it passes vacuously | Guard |
|---|---|
| The load never got going, so there was nothing in flight at the signal. | The run asserts a minimum count of requests that were **in flight at the moment of the signal**, computed from the client's own timestamps. Below it, the run fails as *inconclusive*, not as passing. |
| The slow route was not slow, so everything had finished before the sequence began. | Same counter; and the handler's delay is a parameter of the run, printed with the result. |
| The signal never reached the process — the classic `docker stop` against a shell entrypoint that does not forward signals. | The run asserts the process observed the signal, by requiring readiness to have gone `503` (A4). A process that never saw `SIGTERM` never flips it. |

---

## 3. The property test on the stop order

### 3.1 What it is over

The stage machine of [feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §3 records
a transition for every stage it enters and leaves. The property test drives that machine directly —
no server, no socket — with generated inputs:

- randomised durations for each participant, including durations that exceed the stage's deadline;
- randomised failures: a participant that throws, one that never returns, one that returns
  immediately;
- a randomised *number* of participants in each stage, including zero;
- both platforms, because the machine is common code and the test runs on every target kore
  publishes.

### 3.2 The properties

1. **The recorded transitions are always a prefix of the specified sequence.** Not "contain" and not
   "end with" — a prefix, so a stage that is skipped is a failure and a stage that runs twice is a
   failure.
2. **No stage begins before the previous one has ended**, whatever the durations.
3. **A participant that exceeds its deadline does not extend the stage**, and does not prevent the
   next stage from beginning.
4. **A participant that throws does not stop the sequence.** The failure is recorded and the
   remaining stages still run — a shutdown that aborts halfway leaves exactly the resources open that
   the sequence exists to close.
5. **The total time is bounded by the sum of the stage deadlines**, for every generated input —
   *including* a participant that ignores cancellation. **Sharpened while implementing (B-04):** this
   property is the one that decides an implementation detail rather than merely describing one. Under
   structured concurrency a stage joins its participants on the way out, so a participant that blocks
   in a way `withTimeout` cannot interrupt makes the bound a lie. The machine therefore *detaches*
   the stage's scope and cancels it without joining — the bound is a promise about **waiting**, not
   about stopping, and the generated inputs must include the uncooperative case or the property is
   satisfied by an implementation that does not hold it.
6. **The sequence runs exactly once** under concurrent triggers — two signals, or a signal and a
   programmatic stop.

### 3.3 The test is proved by mutation, and the mutations are named in advance

A property test over a sequence is easy to write so that it holds for any sequence. So the test is
itself under test: each mutation below is applied to the implementation, and the suite **must** go
red. A mutation that survives is a gap in the property, and the item is not done until it fails.

| Mutation | Which property should catch it |
|---|---|
| Swap the release stage and the drain stage | 1 |
| Drop the pre-drain wait to zero | this one is invisible here by design — it is A5 in §2. Named so that the gap is recorded rather than discovered. |
| Run the release stage's participants concurrently with the drain | 2 |
| Let a participant's overrun extend its stage | 3, 5 |
| Join the stage's scope instead of detaching it | 5, and only against an uncooperative participant — killed by hand in B-04 |
| Let a throwing participant abort the sequence | 4 |
| Remove the once-only guard | 6 |

The second row is the honest one: a property test over the machine cannot see a *duration* that has
been set to zero, because zero is a legal duration. That is why §2 and §3 are both gates and neither
replaces the other.

**Three of these were run by hand in B-04**, against the machine and its eleven example-based tests,
before the generative version existed: reversing the specified order (2 of 13 red), joining the
stage's scope instead of detaching it (1 red), and removing the once-only guard (1 red).

**Run again in B-13 against the generated plans**, where a surviving mutant would mean something
different — that the *generator* never produced the case:

| Mutation | Against 200 generated plans |
|---|---|
| reverse the specified order | 13 of 30 red |
| join the stage's scope instead of detaching it | 3 of 30 red, including **two of the generative properties by name** — "a stage never runs longer than its own deadline" and "the total never exceeds the sum of the deadlines" |
| a throwing participant aborts the sequence | red |
| remove the once-only guard | red |
| a DWELL stage skips its wait | 1 of 30 red |

The second row is the one worth reading: the detach is the design decision the machine makes that is
easiest to argue away, and it is the generative properties rather than a hand-written example that
refuse the alternative.

**The suite passed on its first run**, which is a claim about the implementation only because the
mutations above say so. A property test that has never been made to fail is a property test nobody
has any reason to believe.

---

## 4. The numbers

Three, named in the brief. They are **measurements, not gates**: a threshold in milliseconds
measures the machine it ran on. Each is reported as a comparison, and every rule below exists
because the portfolio has paid for it once already.

| Number | What it means | How it is taken |
|---|---|---|
| **Time to first `/health/startup` = 200** | How much of a rollout kore costs before anything is served. | From the container's start to the first successful probe, measured by the harness, not by the process's own log. |
| **RSS at readiness** | What kore costs a resident set — the reason these services are native at all. | Sampled at the moment `/health/ready` first answers `200`, from outside the process. |
| **Stop time under load** | Wall clock from `SIGTERM` to exit, in the §2 run. | The same clock A6 uses. |

### 4.1 The rules for taking them

- **Every number is relative.** Each is reported as *sample against control*: the same binary with
  kore and without it, in the same run, alternating. An absolute figure describes the runner.
- **One run per variant is not a measurement.** Each variant is run repeatedly and reported by
  median with the spread; a single pair of numbers is an anecdote with two decimal places.
- **The first run after a restart measures warm-up.** It is discarded explicitly, and the fact that
  it was discarded is part of the report.
- **A ratio without an absolute decides nothing.** "40 % slower" on a 3 ms start is not a finding;
  both halves are reported.
- **The measurement names what it measured.** Which machine, which container, which load, which
  commit of kore — in the report, not in a note about it.
- **A number in a README is not a measurement.** Figures live in the pull request that produced them
  and in a measurements document; prose elsewhere links to them rather than restating them, because a
  restated figure is one nothing updates.

### 4.2 Where the numbers live

`docs/research/measurements-<YYYY-MM-DD>/`, one directory per run, with the raw output and a
one-page summary. Written when the run happens, not reconstructed. A milestone that claims an effect
on any of the three does not close without one.

---

## 5. What none of this covers

Stated so that a green suite is not read as more than it is:

- **Multiple replicas.** Everything here is one process. Whether a rolling deploy of several
  replicas drops a request depends on the readiness period, the endpoint propagation and the surge
  settings — none of which kore controls, and all of which are the chart's business.
- **A pod that is killed rather than terminated.** `SIGKILL`, an OOM kill and a node going away are
  outside the sequence by definition. kore's promise is about `SIGTERM`.
- **Whether the dependency checks are the right checks.** The oracle proves the *order*; whether a
  `SELECT 1` is a good proxy for "the database will serve my queries" is a question about the
  dependency, answered by the service that owns it.
- **The profiler hook**, which has no oracle yet because it has no defined shape — open question 1 of
  research-architecture §3.
