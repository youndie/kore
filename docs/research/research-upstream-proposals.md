---
id: research-upstream-proposals
title: kore — findings that belong upstream
type: research
status: active
date: 2026-09-11
---

# Findings that belong upstream

kore is a thin library over other people's code, so most of what it does is a workaround, and a
workaround that stays local forever is a fork with extra steps. The portfolio's rule applies here
unchanged: **nothing is forked; a gap goes upstream as an issue, kore works around it locally, and
the workaround carries a comment naming the issue** — so the next person deletes it instead of
inheriting it.

Two different permissions apply:

* **`youndie/*` repositories** — booblik, tracy, metrik, katcher — are the working arrangement and
  need no permission. Filing is the normal thing to do.
* **Anybody else's tracker** — here, JetBrains' — **is asked about first.** An issue costs the
  maintainer time and cannot be quietly withdrawn. §1 below is written up and **not filed**.

Nothing in this document has been filed yet. Every entry says what it would claim, what it is
verified against, and what kore does meanwhile.

---

## 1. Ktor — **not filed, needs a decision first**

### 1.1 `EmbeddedServer.stop` runs its steps in the opposite order on JVM and on Kotlin/Native

**Claim.** On JVM, `stop` drains the engine and then destroys the application. On Kotlin/Native it
destroys the application and then drains the engine. `ApplicationStopping` therefore fires after the
drain on one platform and before it on the other, from identical common code, with nothing in the
documentation saying so.

**Verified against** `ktor-server-core` 3.5.2 published sources:
`jvmMain/io/ktor/server/engine/EmbeddedServerJvm.kt:404-412` against
`posixMain/io/ktor/server/engine/EmbeddedServerNix.kt:84-94`. See
[research-architecture](research-architecture.md) §1.1 for the full table.

**Why it is worth an issue rather than only a workaround.** The idiom it breaks is the one every
Ktor example teaches — close your resources in `ApplicationStopping` — and on Native it closes them
underneath requests that are still being served. The failure is silent in any test that does not
hold a request open across the stop.

**What kore does meanwhile.** It never puts work in `ApplicationStopping`. Its release stage runs
after `EmbeddedServer.stop` has returned, on both platforms
([feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §3).

**Open.** Whether this is a defect or a deliberate difference. The honest first step is a question,
not a bug report — and it needs the measurement of [research-oracle](research-oracle.md) §2 attached,
because "the order differs" is a reading of the source and "a request is dropped because of it" is a
result. Ask the owner before filing; do not file a claim the negative control has not supported.

### 1.2 On Kotlin/Native the shutdown hook is a single global slot, and it runs on the signal stack

**Claim, two halves.** `addShutdownHook` on Native stores the callback in one file-level
`AtomicReference`, so a second caller silently replaces the first — including
`EmbeddedServer.start`'s own hook. And the stored block is invoked from a `staticCFunction` installed
with `signal()`, so it runs arbitrary Kotlin — allocation, locks, `runBlocking` — on the
signal-handler stack, which is not async-signal-safe.

**Verified against** `posixMain/io/ktor/server/engine/ShutdownHookNative.kt` and
`commonMain/io/ktor/server/engine/ShutdownHook.kt` of the same artefact. Ktor's own KDoc states the
first half plainly, which is a point in its favour and also the reason the second half is worth
raising separately: the replacement behaviour is documented, the signal-safety is not.

**What kore does meanwhile.** It does not use `addShutdownHook` at all: it installs its own handler
with `sigaction`, and that handler does nothing but set a flag and wake a parked coroutine
(research D3, and the module reasoning in
[services/kore-library](../services/kore-library.md) §3).

**Open.** Same as §1.1 — ask first. A proposal that the slot become a list would *worsen* kore's
position rather than improve it, because a co-resident hook calling `stop()` directly reintroduces
the unordered path; so if this is raised at all, it is raised as "the callback should not run on the
signal stack", not as "there should be more than one".

---

## 2. metrik — `youndie/metrik`, ready to file

**Claim.** `MetrikAgent.stop()` cancels its job and its scope, closes the sender and closes the
dispatcher, and does not flush the open aggregation window. With the default 60-second window, a
process that stops loses up to a minute of metrics — including everything it served during a drain,
which is the interval an ordered shutdown exists to protect.

Second half: the plugin subscribes the agent to `ApplicationStopping` itself. Given §1.1 that means
the agent stops *before* the drain on Kotlin/Native, so on a native binary the requests served during
shutdown are not measured at all.

**Verified against** `agent/src/commonMain/kotlin/io/github/youndie/metrik/agent/MetrikAgent.kt:128-134`
and `Metrik.kt:89`, plus `DEFAULT_WINDOW_MS` in the shared protocol.

**Proposed shape.** A bounded final flush in `stop()`, mirroring what tracy's delivery already does
deliberately — and a way for a host to stop the agent itself rather than through the event, so a
library that owns the shutdown order can put it where it belongs.

**What kore does meanwhile.** Nothing it can do from outside; the loss is documented in
[feature-observability-wiring](../features/feature-observability-wiring.md) §7 rather than absorbed.

## 3. tracy — `youndie/tracy`, ready to file

**Claim.** `TracyDelivery.stop(grace)` exists, is correct, and is written for exactly this moment —
its own comment says the records produced during a shutdown are the least replaceable ones in the
buffer. Nothing calls it by default: the delivery is a separate object from the plugin, and the
documented wiring is `TracyDelivery(agent, config).start(app)`, which gives the caller no reason to
keep the reference. The portfolio's most complete service does exactly that and loses the last flush
interval on every shutdown.

**Verified against** `agent/src/commonMain/kotlin/io/github/youndie/tracy/agent/TracyDelivery.kt:82-86`
and, for the consequence, `konekt/server/src/main/kotlin/io/konekt/observability/Observability.kt:73`.

**Proposed shape.** Have `start(application)` subscribe `stop` — the delivery already takes the
application, so it can. A correct default beats a correct API nobody calls.

**What kore does meanwhile.** It holds the delivery and calls `stop` in its telemetry group, with its
own deadline ([feature-observability-wiring](../features/feature-observability-wiring.md) §3).

## 4. booblik — `youndie/booblik`, ready to file, and it is one line

**Claim.** The JVM and Native clients of the same broker disagree about what `Producer.close()`
does, and the JVM one is the one that loses data.

Both are `mailbox.close()` with a `finally { drainPending() }`. The **native** `drainPending()`
begins with `sendAll()` and then fails only what was still queued in the mailbox. The **JVM**
`drainPending()` has no `sendAll()`: it completes the accumulated batches exceptionally with
`ConnectionClosedException` instead of sending them. So closing a JVM producer without first calling
`flush()` discards up to a linger window of records, silently — and the records that vanish are
exactly the ones nothing was awaiting.

**Verified against** `booblik-client/src/main/kotlin/io/github/youndie/booblik/net/client/Producer.kt:228-239`
against `booblik-native/src/nativeMain/kotlin/io/github/youndie/booblik/native/Producer.kt:228-242`.

**Proposed shape.** Port the native `sendAll()` into the JVM `drainPending()`. This is deliberately
not the design argument the entry used to carry ("either a flushing `close()`, or a `close()` that is
loud about what it dropped") — the project has already decided what `close()` should mean, in the
implementation it wrote second, and the other one was not brought along.

**What kore does meanwhile.** Its consumer group flushes with a deadline and then closes, in that
order, on both platforms — it does not rely on either client's `close()`, precisely because they
disagree ([feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §2 rule 7).

**How this was found, because the shape recurs.** Not by reading the JVM client more carefully. By
learning that a second implementation existed and asking whether it agreed. One implementation cannot
tell you it is wrong; two can.

## 5. katcher — `youndie/katcher`, a question rather than a defect

**Claim.** `Katcher` is a global object with `start(configure)` and no `stop` and no `flush`. A crash
report produced during shutdown may not be uploaded before the process exits.

**Verified against** `client/src/commonMain/kotlin/io/github/youndie/katcher/Katcher.kt` — `start` is
the only lifecycle function in the file.

**Why it is a question.** For a mobile client, surviving the process is the right design: the report
is persisted and uploaded on the next launch. For a server binary in a container there may be no next
launch on that filesystem. Whether that matters depends on where katcher writes and whether the
volume outlives the pod, which is a question for its owner rather than an assertion from here.

**What kore does meanwhile.** Nothing to call; the gap is recorded in
[feature-observability-wiring](../features/feature-observability-wiring.md) §7.

---

## 6. How a proposal here is closed

Not by the issue being closed. *Closed* and *fixed* are different claims and only the second is
checkable: an entry leaves this document when the fix is read **in the source and in the published
artefact** of the version kore resolves. The portfolio has one standing example of an issue that is
still open while its fix has shipped, and one of a fix that shipped in a release nobody had bumped
to — both of which read identically from the tracker.

When an entry is closed, the workaround it justified is deleted in the same change. A workaround
whose reason is gone is the thing the next reader inherits and preserves.
