---
id: research-architecture
title: kore — architecture research
type: research
status: active
date: 2026-09-11
---

# Research: the architecture of kore

kore is one library that gives every Kotlin service binary in the portfolio the same
process-lifecycle behaviour: an ordered shutdown, three real probes, a typed configuration read
from the environment, one-line wiring of the three observability agents, and a `/version` that
names the commit it was built from. It is the part of a Go service that comes from the standard
library and from habit, written once for Ktor instead of re-derived per service — and written
**native-first**, because the servers it is for are Kotlin/Native binaries.

It is deliberately none of these: a dependency-injection container, a router, or a configuration
framework with seven sources. Those have owners already; what has no owner is the *order* in which
a process stops.

This document records **verified facts** (read in artefacts and in code, with the address), the
**decisions** taken from them, and the **risks**. Anything unverified is called a hypothesis and
says where it will be settled.

There is no kore code yet. Everything in §1 was therefore verified against something that does
exist: the sources of the Ktor version this portfolio pins, the Kotlin/Native platform klibs of the
compiler it uses, the published sources of third-party libraries, the Kubernetes documentation, and
the code of the first consumer and of the four toolkits kore has to wire together.

Companion document: [research-oracle](research-oracle.md) — the acceptance experiment and the
numbers, both written before the code.

---

## 1. Verified facts

### 1.1 Ktor's stop order is *inverted* between JVM and Kotlin/Native

Verified against the published sources of `io.ktor:ktor-server-core` **3.5.2** — the version
[konekt](https://github.com/youndie/konekt) pins — in both the JVM and the `linuxx64` artefacts.

| Fact | Where verified |
|---|---|
| On JVM, `EmbeddedServer.stop` calls `engine.stop(grace, timeout)` **first** and `destroyApplication()` **second** | `ktor-server-core-jvm-3.6.0-sources.jar` → `jvmMain/io/ktor/server/engine/EmbeddedServerJvm.kt:423-431` |
| `destroyApplication()` raises `ApplicationStopping`, calls `application.disposeAndJoin()`, then raises `ApplicationStopped` | same file, `293-306` |
| On Kotlin/Native, `EmbeddedServer.stop` calls `destroyBlocking(application)` **first** and `engine.stop(grace, timeout)` **second** | `ktor-server-core-linuxx64-3.6.0-sources.jar` → `posixMain/io/ktor/server/engine/EmbeddedServer.posix.kt:84-94` |
| `disposeAndJoin()` is `applicationJob.cancelAndJoin()` followed by `uninstallAllPlugins()` | `commonMain/io/ktor/server/application/Application.kt:162-165` |

So the same three lines of user code mean two different things:

```
JVM      stop accepting → drain in-flight → ApplicationStopping → cancel the application → ApplicationStopped
Native   ApplicationStopping → cancel the application → ApplicationStopped → stop accepting → drain in-flight
```

**Measured and confirmed on 2026-09-11 ([B-03](../backlog/B-03-negative-control.md)), and it is worth
reading before the reasoning below.** Same source, same configuration, same load, three runs per
cell: with a stop subscriber that closes a resource the in-flight request uses — the ordinary thing a
service does there — the JVM is **fine** and Kotlin/Native returns **48 responses in 5xx, every
run**. The full matrix is in
[measurements-2026-09-11/negative-control.md](measurements-2026-09-11/negative-control.md).

So the ordering below is not a curiosity read out of two source files; it is a defect that a designed
experiment reproduces on demand.

**Consequence 1 — this is why kore exists.** `ApplicationStopping` is the hook every Ktor example
uses for "close the pool, stop the workers, close the broker connection". On JVM that runs after
the drain, which is correct. On Kotlin/Native it runs *before* the drain, so a service that follows
the idiom pulls its database pool and its broker socket out from under the requests it is still
supposed to be finishing. Nothing in the Ktor documentation says the two differ, both compile from
the same common code, and the difference is invisible in a test that does not hold a request open
across the stop.

**Consequence 2 — the order has to be owned by kore, not by the engine.** kore cannot fix the
engine and will not fork it, so it must not *use* the engine's ordering as its ordering. kore runs
its own sequence around `EmbeddedServer.stop` and treats `ApplicationStopping` as an event it
observes rather than as the place work is done. The public promise "readiness falls, then in-flight
drains, then pools and consumers close, then the process exits" is then true on both platforms
because kore imposes it, which is what "the order is a specification, not a side effect" has to mean
in practice.

**Consequence 3 — a stage that must run after the drain cannot be a Ktor subscriber at all.**
That includes the one stage every consumer has: closing the connection pools. See
[feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §3.

**Hypothesis — settled on 2026-09-11 by running it (B-06), and it was wrong.** This used to ask
whether an in-flight CIO call is a child of `applicationJob` and therefore cancelled by
`disposeAndJoin()` on native, and said "the consequence is the same for kore either way — a request
in flight during `ApplicationStopping` is not safe".

It is not the same, and that sentence was the mistake. Run with a grace period long enough to see
anything, **all eight in-flight requests completed on Kotlin/Native** (§1.13). Whatever
`applicationJob.cancelAndJoin()` cancels, the in-flight calls are not it.

The ordering above stands — it is read directly in the source, and `ApplicationStopping` does fire
before the drain on Native. What changes is the danger: it is not that Ktor kills the request, it is
that **a subscriber closing a pool kills it**, while the request is still being served. Same
requirement on kore, smaller and true claim behind it.

### 1.2 On CIO, `ApplicationStopPreparing` fires *after* the listening socket stops accepting

Verified against `io.ktor:ktor-server-cio` 3.5.2.

| Fact | Where verified |
|---|---|
| `stopSuspend` completes `stopRequest`, waits `gracePeriodMillis` for the server job, then cancels it and waits `timeoutMillis - gracePeriodMillis` | `ktor-server-cio-jvm-3.6.0-sources.jar` → `commonMain/io/ktor/server/cio/CIOApplicationEngine.kt:91-107` |
| The server job, on `stopRequest`, cancels each connector's `acceptJob`, **then** raises `ApplicationStopPreparing`, **then** joins the connectors' root jobs | same file, `250-260` |
| The default idle timeout for a kept-alive connection is 45 seconds | same file, `Configuration.connectionIdleTimeoutSeconds = 45` |

**Consequence 1.** `ApplicationStopPreparing` is the one Ktor event that *sounds* like "we are about
to stop, flip readiness now", and on CIO it is useless for that: by the time it fires the socket has
stopped accepting, so a kubelet probe arriving afterwards gets a connection refused rather than a
503, and the drain deadline is already counting. Readiness must be flipped by kore's own signal
handling, **before** `EmbeddedServer.stop` is called at all.

**Consequence 2.** `timeoutMillis` is an *absolute* budget, not an extra one: the hard-kill window is
`timeoutMillis - gracePeriodMillis`. Configured with `timeout <= grace`, the cancel is followed by a
non-positive timeout and the engine does not wait for the cancellation to take effect. kore
validates the pair at configuration time rather than letting it be discovered during an incident.

**Consequence 3.** During the drain, new **connections** are refused — and that is all. A client
that already holds a keep-alive connection goes on being served normally for the whole grace period,
which §1.13 measured: 48 requests after the signal, none refused. So a probe that dials in during the
drain gets a connection refused, and a probe reusing a connection gets a cheerful `200`. Neither is
the answer kore wants, which is the second reason the pre-drain window in §2 D2 is a stage of its own
rather than a formality — and the reason kore installs a refusal of its own.

### 1.3 On Kotlin/Native the shutdown hook is a single global slot, and it runs inside a POSIX signal handler

Verified by unpacking `ktor-server-core-linuxx64-3.6.0-sources.jar` — and, for the JVM row,
`ktor-server-core-jvm-3.6.0-sources.jar`, because a target's sources jar carries `commonMain` plus
**that target's** source sets and no others. This section used to name only the first, which was
true of two of its three rows; each address below now carries the artefact it was read out of.

| Fact | Where verified |
|---|---|
| `addShutdownHook` is common API with a per-platform actual | `ktor-server-core-linuxx64-3.6.0-sources.jar!/commonMain/io/ktor/server/engine/ShutdownHook.kt` |
| On Native the callback is stored in one file-level `AtomicReference`, so **each call replaces the previous one** | `ktor-server-core-linuxx64-3.6.0-sources.jar!/posixMain/io/ktor/server/engine/ShutdownHook.posix.kt` |
| The handler is installed with `signal(SIGINT, …)` / `signal(SIGTERM, …)` and a `staticCFunction` that reads that global | same file |
| `EmbeddedServer.start` on Native itself calls `addShutdownHook { stop() }` | `posixMain/io/ktor/server/engine/EmbeddedServer.posix.kt:49-50` |
| Ktor's own KDoc states it: *"On Native, each call replaces the previous callback; only the last registered `stop` block is kept"* and *"the built-in `EmbeddedServer.start` hook typically wins"* | `ktor-server-core-linuxx64-3.6.0-sources.jar!/commonMain/io/ktor/server/engine/ShutdownHook.kt`, `ktor-server-core-linuxx64-3.6.0-sources.jar!/posixMain/io/ktor/server/engine/ShutdownHook.posix.kt` |
| On JVM the same call adds an independent `Runtime.getRuntime().addShutdownHook` thread, several may coexist, and the order between them is explicitly unspecified | `ktor-server-core-jvm-3.6.0-sources.jar!/jvmMain/io/ktor/server/engine/ShutdownHookJvm.kt` |
| On JVM the mechanism can be switched off entirely by the system property `io.ktor.server.engine.ShutdownHook` | same file, `SHUTDOWN_HOOK_ENABLED` |

**Consequence 1.** kore may not deliver its shutdown through `addShutdownHook`. On Native it would
either silently replace `EmbeddedServer.start`'s hook or be silently replaced by it — whichever ran
last — and "whichever ran last" is a registration order nobody writes down. kore installs its own
handler and **calls `EmbeddedServer.stop` itself**, which also makes the JVM and the Native paths one
piece of code rather than two.

**Consequence 2 — and this is a safety property, not a preference.** Ktor's native handler runs
arbitrary Kotlin, including `runBlocking`, on the signal-handler stack. That is not async-signal-safe:
allocation, locks and the coroutine machinery are all reachable from it. kore's own handler must do
the minimum a signal handler may do — set a flag and wake something — and let an ordinary coroutine
on an ordinary thread run the sequence. `platform.posix` exposes `sigaction`, `sigemptyset` and
`sigfillset` on `linux_x64` and `linux_arm64` (verified by dumping
`kotlin-native-2.4.20!/klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.posix`).

**Amended while implementing (B-08): kore uses `signal()`, not `sigaction()`, and the choice is not
the interesting one.** `struct sigaction` has a different shape on Linux and on Darwin —
`__sigaction_handler` against `__sigaction_u` — and all three native targets share one source set, so
`sigaction` would mean two implementations of a handler that writes one integer. What separates kore
from Ktor here is *what the handler does*, not what installs it. glibc's `signal()` keeps the handler
installed after it fires, which matters because the alternative semantics would let a second
`SIGTERM` reach the default disposition and kill the process mid-sequence — asserted by raising
`SIGTERM` twice in a test rather than taken from a manual page.

**Consequence 3.** Because Ktor's own hook is installed by `start()` and cannot be removed, kore has
to be the thing that is *later*: it registers after the server has started, and accepts that on
Native its handler is the surviving one by construction. That is an ordering dependency worth a
test of its own rather than a comment — see the property test in
[research-oracle](research-oracle.md) §3.

### 1.4 `Connection: close` on a response does not make CIO close the connection

The brief's oracle asks for a 503 carrying `Connection: close`. Two separate questions: may the
header be set, and does setting it do what the name suggests.

| Fact | Where verified |
|---|---|
| Ktor's unsafe-header list is exactly `Transfer-Encoding` and `Upgrade`, so `Connection` may be appended by ordinary application code | `ktor-http-jvm-3.6.0-sources.jar!/commonMain/io/ktor/http/HttpHeaders.kt`, `UnsafeHeadersArray` |
| CIO writes the response headers to the wire verbatim and adds no `Connection` logic of its own | `ktor-server-cio-jvm-3.6.0-sources.jar!/commonMain/io/ktor/server/cio/CIOApplicationResponse.kt`, `sendResponseMessage` |
| Whether CIO ends the connection after a response is decided by `isLastHttpRequest(version, connectionOptions)`, where `connectionOptions` is parsed from the **request's** `Connection` header | `ktor-server-cio-jvm-3.6.0-sources.jar!/commonMain/io/ktor/server/cio/backend/ServerPipeline.kt` |

**Consequence.** The header is emitted and a well-behaved client honours it, but the *server* keeps
the keep-alive connection in its pipeline loop regardless. The socket goes away when the client
closes it, when the 45-second idle timeout expires, or when `stop()`'s grace period runs out and the
job is cancelled. So the oracle can assert the header — that is a real, checkable promise to the
client — and must **not** assert that the connection was closed by the server, because CIO does not
offer that and an assertion that passes for the wrong reason is worse than none. Stated as a
deviation from the brief in D6.

### 1.5 There is no `System.getenv()` on Kotlin/Native, and enumerating the environment is per-target

The configuration feature's most valuable promise — *fail on an unknown variable* — needs the
ability to list what is in the environment, not just to ask for names one at a time.

| Fact | Where verified |
|---|---|
| Kotlin/Native offers `getenv(name)` and nothing that enumerates; the portfolio already works around it with an `expect fun readEnv(name: String): String?` | [tracy](https://github.com/youndie/tracy) `server/src/commonMain/kotlin/io/github/youndie/tracy/server/ServerConfig.kt` and its two actuals |
| `platform.posix` on `linux_x64` and `linux_arm64` exposes the glibc global as **`__environ`** — not `environ` | Kotlin/Native 2.4.20 distribution, `klib dump-metadata` against `kotlin-native-2.4.20!/klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.posix` |
| `platform.posix` on `macos_arm64` exposes **neither** `environ` nor `__environ` | same command against `kotlin-native-2.4.20!/klib/platform/macos_arm64/...posix`; both greps are empty |
| `_NSGetEnviron`, the documented macOS replacement, is not in `platform.posix`, `platform.darwin` or `platform.Foundation` either — reaching it needs a cinterop `.def` of one's own | the same `klib dump-metadata` against those three klibs; all three greps return 0 |

**Consequence 1.** The strict-unknown check is a capability of the **JVM and Linux native** targets.
On macOS native it cannot be implemented out of the platform libraries, and kore must say so out
loud rather than quietly returning "no unknown variables found" — a check that always passes is
worse than an absent one, because a deployment reads it as evidence.

**Consequence 2.** Since the servers kore is for run on Linux and macOS native exists to let the same
binary build on a laptop, the degradation is acceptable *if it is visible*: `--print-config` states
which target it is running on and whether unknown-variable detection is available there.
See [feature-typed-config](../features/feature-typed-config.md) §7.

**Consequence 3.** "Unknown variable" cannot mean "any variable this process did not declare" in any
case. A container's environment carries `PATH`, `HOSTNAME`, `KUBERNETES_SERVICE_HOST` and every
`*_PORT` variable the kubelet injects. The check is scoped to a declared prefix, and the prefix is
part of the schema. Without that the feature is unusable on its first deployment, which is exactly
where it would be switched off and never switched on again.

### 1.6 What the three observability agents actually do when a process stops

The brief treats "logs to tracy, metrics to metrik, crashes to katcher" as one line of wiring. Read
in the agents, it is three different shutdown contracts.

| Fact | Where verified |
|---|---|
| tracy's delivery has `suspend fun stop(grace)`: it cancels its loop and makes one last bounded flush, deliberately, because *"the records produced during a shutdown … are the least replaceable ones in the buffer"* | [tracy](https://github.com/youndie/tracy) `agent/src/commonMain/kotlin/io/github/youndie/tracy/agent/TracyDelivery.kt:82-86` |
| Nothing subscribes that `stop` to anything. The first consumer constructs the delivery, calls `start(this)` and discards the reference, so `stop` cannot be called | [konekt](https://github.com/youndie/konekt) `server/src/main/kotlin/io/konekt/observability/Observability.kt:73` |
| metrik's agent **does** stop itself on `ApplicationStopping`, and its `stop()` cancels the job, cancels the scope, closes the sender and closes the dispatcher — with **no flush** of the open window | [metrik](https://github.com/youndie/metrik) `agent/src/commonMain/kotlin/io/github/youndie/metrik/agent/Metrik.kt:89` and `MetrikAgent.kt:128-134` |
| metrik's aggregation window defaults to 60 seconds | `metrik/shared/.../Protocol.kt`, `DEFAULT_WINDOW_MS`, and the comment recording it in konekt's `ObservabilityConfig.kt:22-30` |
| katcher is a global `object` with `start(configure)` and **no `stop` and no `flush`** — read at `b9b953f`, and **no longer true since client 0.7.47** (amended 2026-09-12): `flush(grace)`, `cacheDir` and `crashUploadGrace` were added in answer to [katcher#50](https://github.com/youndie/katcher/issues/50), which this reading raised. Amended rather than rewritten: the row is what was read, and a superseded reading left standing is §1.7's own failure | [katcher](https://github.com/youndie/katcher) `client/src/commonMain/kotlin/io/github/youndie/katcher/Katcher.kt:77` then; `Katcher.flush` and `ReportUploader.kt` at `fbfedf1` now |
| katcher's native crash hook is `setUnhandledExceptionHook`, chained onto the previous hook — not a POSIX signal handler, so it does not collide with §1.3 | `client/src/nativeMain/kotlin/io/github/youndie/katcher/Katcher.native.kt` |

**Consequence 1.** "One line of wiring" is worth having precisely because these three do not agree.
kore owns three different stages: *ask tracy to flush and wait, bounded*; *let metrik's own
subscription run, and know that the open window is lost*; and — since 0.7.47 — *ask katcher to flush
too, on the same deadline*. The third used to read "do nothing for katcher, because there is nothing
to call", and the difference between then and now is an issue that got answered, not a re-reading.

**Consequence 2 — a defect in the first consumer, found by reading rather than by an incident.**
konekt loses the last flush interval of tracy records on every single shutdown, because the object
that could flush them is unreachable. That is the shape of failure kore is for: nothing is wrong
with either library, and the wiring between them was written once, correctly enough to start, and
never revisited. Carried as [B-31](../backlog/B-31-first-consumer-findings.md).

**Consequence 3 — and it is specific to Native.** metrik stops on `ApplicationStopping`. By §1.1
that event fires *before* the drain on Kotlin/Native, so on a native binary the requests served
during the drain are not measured at all — and those are exactly the requests an ordered shutdown
exists to protect. kore's ordering fixes this as a side effect of fixing §1.1, which is worth
noticing: the same misordering costs correctness in one place and observability in another.

**Consequence 4.** All three agents publish `jvm`, `linuxX64`, `linuxArm64` and `macosArm64`
(read in `agent/build.gradle.kts` of tracy and metrik, `client/build.gradle.kts` of katcher), so
kore's own target set can match them and the wiring can live in common code.

**Which is necessary and not sufficient — see §1.12.** Publishing the right *targets* and being
*resolvable* are two claims, and the second one was assumed here until B-01 tried it.

### 1.7 booblik has a Kotlin/Native client, and the decision saying it does not is superseded

| Fact | Where verified |
|---|---|
| `booblik-client` and `booblik-net` are `kotlin("jvm")` modules; there is no multiplatform variant | [booblik](https://github.com/youndie/booblik) `booblik-client/build.gradle.kts`, `booblik-net/build.gradle.kts` |
| The decision is recorded, not accidental: *"Р8. Клиент остаётся Kotlin/JVM"* | `booblik/docs/research/research-architecture.md` §Р8 |
| What is not portable is enumerated there: sockets, `ByteBuffer`, two primitives from `java.util.concurrent`, `CRC32C` | same section, and the header comment of `booblik-client/build.gradle.kts` |

**Correction found while doing B-01 (2026-09-11).** This section used to end here, concluding that
a native-first library cannot take a JVM-only dependency in common code and that the booblik adapter
is therefore JVM-only — which was recorded as D5. **That conclusion is wrong, and the three facts
above are all still true.** They are true *about those two modules*. What they are not is the whole
of booblik:

| Fact | Where verified |
|---|---|
| `io.github.youndie.booblik:booblik-native` **0.3.3** is published on Maven Central with `linuxX64` and `macosArm64` variants, alongside `booblik-protocol-linuxx64` and `-macosarm64` | the Central listing of `io/github/youndie/booblik/`, read 2026-09-11 |
| It is a real client, not a stub: `Connection`, `Consumer`, `Producer`, `Socket` | `booblik/booblik-native/src/nativeMain/kotlin/io/github/youndie/booblik/native/` |
| It came from a multiplatform *split of the protocol* rather than a reimplementation — its own header says so, crediting milestone M-134 | `booblik/booblik-native/build.gradle.kts` |

So the force behind D5 is gone. What remains true is that there are **two clients with two different
package names and two different APIs** (`io.github.youndie.booblik.net.client` on the JVM,
`io.github.youndie.booblik.native` on Native), so a single common adapter is still not on the table —
but "JVM only" is no longer the answer either. The replacement is a question rather than a new
decision, because the choice between one JVM adapter now and two adapters over two clients has a
price and an owner: [B-36](../backlog/B-36-booblik-adapter-targets.md).

**Why this was wrong, which is the part worth keeping.** The decision text — *"Р8. Клиент остаётся
Kotlin/JVM"* — is still in booblik's research document, still accurate about the day it was written,
and was superseded by a later milestone that did not go back and amend it. Reading a recorded
decision is not the same as reading the registry. The general rule: **a decision found in somebody
else's document is a fact about the past; the published artefacts are the fact about now.**

**Consequence that survives the correction.** The brief names booblik consumers as one of kore's
shutdown stages, and kore still defines the *stage* abstractly rather than depending on booblik from
`kore-core`. That was never only about portability: the stage is "things that hold a socket and a
position and must be told to stop before the pools close", and booblik consumers are one instance of
it.

### 1.8 A producer closed and torn down in the same breath loses its accumulated batch

Found while reading §1.7, and it is the concrete reason the consumer stage has to distinguish
*flush* from *close*.

| Fact | Where verified |
|---|---|
| `Producer.close()` is one line: `mailbox.close()` | `booblik/booblik-client/src/main/kotlin/io/github/youndie/booblik/net/client/Producer.kt:118-120` |
| The producer's loop ends in `finally { drainPending() }`, and `drainPending` completes every queued record **exceptionally** with `ConnectionClosedException` — it does not send them | same file, `228-239` |
| `flush()` exists, is `suspend`, and is the only thing that pushes the accumulator | same file, `112-117` |
| The accumulator's default linger is 5 ms and its default batch is 100 records | same file, `ProducerConfig` |
| The first consumer's shutdown calls `producer.close()` with no preceding `flush()` | [konekt](https://github.com/youndie/konekt) `server/src/main/kotlin/io/konekt/events/BrokerConnection.kt:110-126` |

**Amended while doing B-01 (2026-09-11), and the amendment makes the finding stronger.** Having
learned from §1.7 that a Kotlin/Native client exists, the obvious next question was whether it does
the same thing. It does not:

| Fact | Where verified |
|---|---|
| The **native** `Producer.close()` is also `mailbox.close()` (plus `dispatcher.close()`), with the same `finally { drainPending() }` | `booblik/booblik-native/src/nativeMain/kotlin/io/github/youndie/booblik/native/Producer.kt:84-89`, `:124-127` |
| But the native `drainPending()` **begins with `sendAll()`** and only then fails whatever is still queued in the mailbox | same file, `:228-242` |
| The JVM `drainPending()` has no `sendAll()`: it fails the accumulated batches too | `booblik-client/.../Producer.kt:228-239` |

So the two published clients of the same broker disagree about what `close()` means. On Native the
accumulated batch is sent; on the JVM it is discarded. That is a sharper claim than the original one
and a more useful one: the fix upstream is not a design argument, it is one line that already exists
in the sibling implementation.

**Refuted by measurement while doing [B-45](../backlog/B-45-booblik-against-a-real-broker.md) (2026-09-12), and the
heading above is the sentence that was wrong.** Everything in the two tables is still true of the
source; the *link between them* is not. `drainPending()` is not the closing path. Closing the mailbox
makes the accumulation window's `select` take the closed branch, which breaks the window and falls
into the `sendAll()` at the foot of `runLoop` — the same call the linger timer would have reached —
so by the time `finally { drainPending() }` runs there is nothing left in `pending` to fail. booblik
now says so in as many words on `Producer.close()`, with `ProducerCloseFlushTest` behind it; the
report this finding became, [youndie/booblik#68](https://github.com/youndie/booblik/issues/68), was
closed as not confirmed.

**What actually loses records, measured against a real broker** — `samples/oracle/.../BrokerFlush.kt`,
three arms against `ghcr.io/youndie/booblik:latest`, five runs, identical every time
([write-up](measurements-2026-09-12/broker-flush.md)):

| Shutdown | Records read back, of 51 |
|---|---|
| `producer.close()`, then `connection.close()` and `scope.cancel()` in the same breath | **1** — only the awaited warm-up |
| `producer.close()`, 500 ms of quiet, then the teardown | **51** |
| kore's `booblikParticipant(…).stop()` — flush awaited, then close — then the teardown | **51** |

So `close()` does send the batch. It sends it **on the producer's own coroutine and does not wait**,
and a shutdown is precisely the moment that coroutine's scope and connection are being torn down.
The loss is a race, and the second arm is what names it: the same `close()` keeps all 51 records when
anything at all waits afterwards.

**Consequence, and it survives the refutation with a different reason.** "Close the consumers and the
pools" is not one verb. The verb that is missing is not *send* but *wait*: a stage that closes and
tears down silently drops up to a linger window of published events on every deployment — a small
number, always, and invisible, because the records that vanish are the ones the process never got an
acknowledgement for. kore's consumer stage is *flush with a deadline, then close*, in that order, on
both platforms; `flush()` is the only call in that client that suspends until the broker has answered,
which is why kore uses it rather than relying on `close()` — not because `close()` discards anything.
The deadline is what stops a flush against a dead broker from eating the whole grace period. Also
carried as a finding against the first consumer in
[B-31](../backlog/B-31-first-consumer-findings.md).

**The shape of the original error is worth more than the correction.** Two true quotations, from two
real files, joined by an inference nobody ran: `close()` ends at `drainPending()`, `drainPending()`
fails the batch, therefore `close()` loses the batch. The middle step was false and no amount of
re-reading the two quotations would have shown it — only executing the path did. The native client's
`sendAll()` in `drainPending()` is a second belt for its own cancellation reasons, not the fix for a
gap on the JVM.

**And a fact picked up in passing, which kore's own scopes need.** booblik's native module records
that **`Dispatchers.IO` is `internal` on Kotlin/Native** — checked there by compiling against
coroutines 1.11.0 rather than read in the documentation, "which says otherwise" — so there is no IO
pool to offload a blocking call onto, and `newSingleThreadContext` is what that module uses instead.
kore's own background loops (the health refresh of §1.9, the stage machine's parked coroutine of
§1.3) have the same problem and it is not solved here. Address: [B-38](../backlog/B-38-native-dispatcher.md).

### 1.9 sqlx4k's connection pool has no `ping`

The brief names a pool `ping` as the readiness check for the database.

| Fact | Where verified |
|---|---|
| `ConnectionPool` declares `poolSize()`, `poolIdleSize()`, `suspend acquire(): Result<Connection>` and `suspend close(): Result<Unit>` — and nothing else | `io.github.smyrgeorge:sqlx4k:1.13.0!/commonMain/io/github/smyrgeorge/sqlx4k/ConnectionPool.kt` |
| 1.13.0 is the version the three native services in the portfolio pin | `gradle/libs.versions.toml` of tracy, metrik and katcher |

**Consequence 1.** On the native side the readiness check for a database is *run a trivial statement
and bound it*, not *ask the pool whether it is fine*. `acquire()` on its own proves a connection
object was handed out, which a pool can do from its idle set without the server on the far end being
alive — that is the check that reports healthy through an outage.

> **Observed 2026-09-12 ([B-47](../backlog/B-47-driver-behaviour-check.md)), and it needed a
> distinction this paragraph did not make.** The sentence above was a derivation; it is now a
> measurement, against a real Postgres with `sqlx4k-postgres` 1.13.0 —
> [raw output](measurements-2026-09-12/raw-driver-behaviour.txt).
>
> | how the server went away | `acquire()` | a statement |
> |---|---|---|
> | a clean `stop` — the peer closes the connections | **fails** | fails |
> | **paused** — the process frozen, sockets open, nothing answering | **succeeds** | **hangs**, cut off at a 10 s bound |
>
> So the consequence holds **only for the silent case**, and that is the case it is about: a network
> partition, a wedged server, a node that went away. A server that shuts down cleanly tells its
> clients, and the pool then fails `acquire` too — which nobody doubted and which this paragraph
> never claimed, but also never excluded.
>
> **The half worth more than the confirmation:** in the silent case the statement does not *fail*, it
> **hangs**. `PooledStoreCheckTest`'s double throws, which is faithful about the asymmetry and not
> about its shape — and the shape is the reason `HealthCheck` carries a timeout at all, and the
> reason Risk 3 exists. A check written against "the statement throws" and deployed against "the
> statement hangs" is a probe that stops answering rather than answering `503`.
>
> The first version of this run measured only the clean `stop` and would have reported the
> consequence **refuted** — a wrong verdict from an experiment testing a different question. The
> container helper's own comment said a clean shutdown is "the case a pool is least likely to
> notice"; it is the case it notices most easily, because the peer sends `FIN`.

**Consequence 2.** `close()` being `suspend` and returning a `Result` is convenient: the pool stage
composes with the rest of the sequence without a thread hand-off, and a close that fails is a value
rather than an exception thrown from a shutdown path.

**Consequence 3 — a bound is not free.** A blocking call is not interrupted by `withTimeout`; the
timeout returns and the call goes on holding its thread. Any dependency check kore ships must
therefore be genuinely suspending, or be run somewhere its overrun cannot consume the drain budget.
This has bitten the portfolio before and is not re-derived here.

### 1.10 What Kubernetes actually does on pod deletion — readiness is not what removes the pod

Verified against the Kubernetes documentation sources
(`kubernetes/website!/content/en/docs/concepts/workloads/pods/pod-lifecycle.md` and `probes.md` beside it,
fetched 2026-09-11).

| Fact | Where verified |
|---|---|
| *"At the same time as the kubelet is starting graceful shutdown of the Pod, the control plane evaluates whether to remove that shutting-down Pod from EndpointSlice objects"* — the two are concurrent | `pod-lifecycle.md`, "Pod Termination Flow", step 3 |
| *"Terminating endpoints always have their `ready` status as `false`"*, independent of the container's own readiness probe | same section |
| The default `terminationGracePeriodSeconds` is 30 seconds; a `preStop` hook runs **before** `TERM`, and if it outlives the grace period the kubelet grants a one-off 2-second extension | same section, steps 1–2 |
| A failing readiness probe makes the EndpointSlice controller remove the Pod's IP from the Services that match it | `pod-lifecycle.md`, "Readiness probe" |
| If a startup probe is configured, liveness and readiness probes are not executed until it succeeds | `pod-lifecycle.md`, "Startup probe" |
| Probe defaults: `initialDelaySeconds` 0, `periodSeconds` 10, `timeoutSeconds` 1, `successThreshold` 1, `failureThreshold` 3 | `probes.md`, "Configure Probes" |

**Consequence 1 — the brief's first step is necessary and not sufficient, and the difference matters.**
"readiness → false" does not evict the pod from the load balancer during an ordinary rollout: the
control plane has already marked the terminating endpoint `ready: false` on its own. What flipping
readiness in-process buys is (a) the truth, for anything that asks the pod directly — an external
load balancer, an ingress controller or a mesh sidecar that polls the probe rather than watching
EndpointSlices — and (b) the same behaviour outside Kubernetes, where nothing else marks anything.
What actually stops traffic arriving after `TERM` is **time**: the interval between the readiness
signal and the start of the drain, long enough for the rule change to reach every node.

**Consequence 2.** So the pre-drain delay is a stage with a number, not a formality, and the number
has a floor: one readiness period times the failure threshold, plus propagation. kore's default is
derived from that floor and stated in [feature-ordered-shutdown](../features/feature-ordered-shutdown.md)
§2 rather than picked to look round.

**Consequence 3.** The whole sequence has to fit inside `terminationGracePeriodSeconds`, whose
default is 30. kore's defaults must sum to less than that with room to spare, and kore must say
what it needs so a chart can raise it — a grace period smaller than the configured sequence is a
`SIGKILL` in the middle of a drain, which looks exactly like a crash.

**Consequence 4.** `startupProbe` suppressing the other two is what makes three separate probes worth
having rather than one path served three times: a slow start is not a liveness failure, and a
dependency that is briefly away is not a reason to restart. That is the distinction
[feature-health-probes](../features/feature-health-probes.md) §2 is built on.

### 1.11 The first consumer today: three probes on one route, and no `/version`

Read in [konekt](https://github.com/youndie/konekt) at the commit in the working tree on 2026-09-11.
It is the most complete service in the portfolio and it is what kore's first release has to improve
on, so what it does now is a baseline rather than a criticism.

| Fact | Where verified |
|---|---|
| `startupProbe`, `livenessProbe` and `readinessProbe` all point at `GET /health` | `konekt/charts/konekt/templates/server.yaml:103-118` |
| `/health` is `call.respondText("ok")` — it touches no dependency | `konekt/server/src/main/kotlin/io/konekt/Application.kt:188` |
| There is no `/version` route and no build-identity constant anywhere in the repository | a grep for `"/version"`, `gitCommit`, `buildTime` and `BuildInfo` across the repository returns nothing |
| The deployment's identity travels as the `RELEASE` environment variable, set from the chart, and is used to name a metrik deploy marker and a katcher crash group | `konekt/charts/konekt/templates/server.yaml:80-82`, `konekt/server/.../observability/ObservabilityConfig.kt:38-45` |
| Shutdown is one `ApplicationStopping` subscriber: cancel the worker scope, close the broker connection | `konekt/server/src/main/kotlin/io/konekt/Application.kt:412-417` |
| Configuration is a hand-written `fromEnv()` of ~35 lines: `System.getenv(...)`, `?:` for defaults, `error(...)` for four required keys | `konekt/server/src/main/kotlin/io/konekt/KonektConfig.kt:57-96` |
| The observability half of the configuration is separate and already has the rule kore generalises: an endpoint without its key is a **refusal at startup**, not a silent no-op | `konekt/server/.../observability/ObservabilityConfig.kt:48-69` |

**Consequence 1.** Everything in the brief is a real gap in a real service, not a hypothetical one.
The chart's own comment argues correctly that a probe must not read the store — and then uses the
same route for readiness, which is the one probe that *should* answer for dependencies.

**Consequence 2 — the good half is the specification.** `ObservabilityConfig`'s "both variables or
neither, and one alone fails the start" is exactly the behaviour kore's config schema should make
declarative instead of hand-written, and the comment explaining why is the argument for the whole
feature: *"a deployment that believes it is observed and is not"*. kore does not invent that rule; it
takes it from the one place it was already got right and makes it cheap enough to be everywhere.

**Consequence 3.** konekt is a JVM service. It is the first consumer, and it is not the primary
target — see D1. Its value here is that it is the one place where all five gaps are visible at once
and where the fix can be measured against a before.


### 1.12 The three observability agents are not on Maven Central

Found while doing B-01, by trying to resolve them rather than by reading a build file.

| Fact | Where verified |
|---|---|
| `io.github.youndie:tracy-agent`, `metrik-agent` and `katcher-client` all answer **404** on Maven Central | `repo1.maven.org/maven2/io/github/youndie/<artifact>/maven-metadata.xml`, read 2026-09-11 |
| The group directory on Central holds kompot, petich, viddik, wizard-core, form-*, experiments-core, chronik, bochka and booblik — and none of the three agents | the Central listing of `io/github/youndie/` |
| booblik *is* there, under its own group `io.github.youndie.booblik`, at 0.3.3 | the Central listing of `io/github/youndie/booblik/` |
| The agents are published to the portfolio's own repository instead, which the first consumer declares with a group filter | [konekt](https://github.com/youndie/konekt) `settings.gradle.kts` |

**Consequence 1, and it is about what kind of thing kore is.** kore is public and meant to be
consumed. A module that declares a repository outsiders cannot reach is a module that fails to
resolve for them — not at build time with a clear message, but as a missing version, which reads as
a broken release. So `kore-observability` ships with **no agent dependencies at all** until this is
decided, rather than with a repository line that works on one machine.

**Consequence 2, and it has been decided.** This was not kore's decision to make on its own: the fix
was either publishing three other projects to Central, or accepting that one kore module is
portfolio-only and saying so. **The owner chose the portfolio's own repository**
(2026-09-12, [B-37](../backlog/B-37-agents-not-on-central.md)):
`https://reposilite.kotlin.website/snapshots`, declared in `settings.gradle.kts` with a group filter,
the same declaration the first consumer already carries.

What that costs is **one module, not the library**. `kore-core` and `kore-ktor` resolve from Maven
Central alone; only `kore-observability` needs the portfolio repository, so a consumer outside it
gets the ordered shutdown, the probes, the configuration schema and `/version`, and cannot resolve
the one module that wires three agents it does not have either. Said in the README and in
[services/kore-library](../services/kore-library.md) §5 rather than discovered at resolution time.

**Verified on 2026-09-12, for all four targets rather than for the JVM:** `tracy:agent:0.2.15`,
`metrik:agent:0.2.18` and `katcher:client:0.7.44` resolve with their transitive `shared` modules on
`jvm`, `linuxX64`, `linuxArm64` and `macosArm64` — and the same for `katcher:client:0.7.47`, which is
what kore resolves since §5 of the upstream proposals was answered; re-run the same way rather than
assumed from the older row. That check is the point of this entry rather than a
formality — an agent that published only a JVM variant would satisfy a common source set at
resolution time and fail the thing kore is native-first for. [B-28](../backlog/B-28-observability-wiring.md)
is unblocked.

**Consequence 3 — a rule this cost.** §1.6 read the agents' `build.gradle.kts` and concluded their
targets were right, which they are. It did not ask whether a stranger could resolve them, which they
cannot. **Reading a build file tells you what a project publishes; only the registry tells you where
it landed.** The same slip produced §1.7.


### 1.13 CIO does not refuse anything while it drains — it keeps serving, for the whole grace period

Found by **running** the oracle (B-06), not by reading. It is the most consequential thing in this
document after §1.1, and it contradicts what §1.1 was taken to imply.

The experiment: the sample service under closed-loop load, eight keep-alive connections on a route
taking 3 s, `SIGTERM` to PID 1, and the client's own record read afterwards.

| Fact | Where verified |
|---|---|
| `ApplicationEngine.Configuration.shutdownGracePeriod` defaults to **1000 ms** and `shutdownTimeout` to **5000 ms** | `ktor-server-core` 3.5.2, `commonMain/io/ktor/server/engine/ApplicationEngine.kt:61` and `:68` |
| At the default, **every** in-flight 3-second request is cut off — on **both** platforms | oracle run, `kore-sample:jvm` and `:native`: A1 failed 8 of 8, "the connection closed before a status line", process gone in 1435 ms / 1599 ms |
| With `shutdownGracePeriod = 20 s`, **every** in-flight request completes — on **both** platforms | oracle run, `kore-probe:jvm` and `:native`: A1 passed, 8 of 8 |
| During those 20 s the server **kept serving new requests** on the already-open connections: 48 exchanges after the signal, **none** refused, no `503`, no `Connection: close` | the same run's record: `exchanges: 64, spanning the signal: 8, after it: 48` |
| Both processes exited at ~20.5 s — the **whole** grace period, not when the work finished | `exit 0 after 20568ms`, `exit 143 after 20452ms` |

**Consequence 1 — the headline. "Drain" in Ktor means "keep serving until the grace expires".** It
does not mean "finish what is in flight and stop". §1.2 read correctly that the accept job is
cancelled; what that stops is **new connections**, not new **requests**, and a keep-alive client goes
on being served for the entire period. So a `drainDeadline` is not an upper bound on shutdown time —
under sustained load it **is** the shutdown time, every time.

**Consequence 2 — kore has to implement the refusal itself, and nothing said so.**
[feature-ordered-shutdown](../features/feature-ordered-shutdown.md) rule 4 promises that a request
arriving after the announce stage is refused with `503` and `Connection: close`. **Ktor does not do
that and will not.** It is a plugin kore installs, gated on the sequence having begun — otherwise
there is no refusal anywhere and A3 has no subject, which is exactly what the run reported.

**Consequence 3 — the default grace period is the first thing to fix, and it is not the ordering.**
One second is shorter than a great many real requests. At the default, both platforms drop in-flight
work for the same simple reason, and §1.1's ordering difference is invisible underneath it. kore must
set `shutdownGracePeriod` and `shutdownTimeout` explicitly from its own stage deadlines and never
inherit these.

**Consequence 4 — §1.1's hypothesis is refuted, and the fact behind it stands.** §1.1 recorded, as an
explicit hypothesis for M2, that an in-flight call might be cancelled by `disposeAndJoin()` on
Kotlin/Native, where `ApplicationStopping` fires before the drain. With a grace period long enough to
see it: **all eight in-flight requests completed on native.** So whatever `applicationJob.cancelAndJoin()`
cancels, the in-flight calls are not it.

What is **not** refuted is the ordering itself — `ApplicationStopping` still runs before the drain on
Native, read directly in the source. What changes is the consequence: the danger is not that Ktor
kills the request, it is that **a subscriber closing a pool does**, while the request is still being
served. That is a smaller claim than the one §1.1 carried, and a true one.
[B-14](../backlog/B-14-inflight-hypothesis.md) is closed by this and records it.

**Consequence 5 — an experiment has to be able to see what it is looking for.** At Ktor's default
grace period the oracle cannot distinguish "the ordering is wrong" from "the budget was one second",
because the second failure happens first and looks identical. The negative control of
[research-oracle](research-oracle.md) §1 must therefore be run at **both** settings, and say which is
which. A run at the default alone would have produced a confident, wrong conclusion — it nearly did.


### 1.14 `Dispatchers.IO` on Kotlin/Native is an extension property — and the error when you miss it says `internal`

> **Refuted 2026-09-12, the same day it was written, and the refutation is the more useful finding.**
> What this section said — that `Dispatchers.IO` is `internal` on Kotlin/Native — is **false**. It is
> an **extension property** in package `kotlinx.coroutines`, so it needs `import
> kotlinx.coroutines.IO` of its own. Without that import the compiler resolves the *internal member
> of the same name* beside it and reports `"it is internal"`. That message is not a platform
> limitation; it is the name resolving to the wrong declaration.
>
> Verified here at coroutines 1.11.0: with the import, `:kore-core:compileKotlinLinuxX64` and
> `:kore-core:compileKotlinMacosArm64` both succeed, and `withContext(Dispatchers.IO) { 3 + 4 }`
> **runs** under `linuxX64Test`. Compiling was not enough to check, because the original error was a
> compile error.
>
> **How this got written is the part worth keeping.** The claim was inherited from booblik and the
> section below congratulates itself for not inheriting it — for compiling a probe instead of
> trusting a neighbour. The probe reproduced the neighbour's own mistake exactly: same missing
> import, same error, same conclusion. *Verifying a claim by the method that produced it is not
> verification.* booblik had already corrected itself the previous evening
> (`docs(native): Dispatchers.IO is not internal on Kotlin/Native`, 2026-09-11) — the answer existed
> in the repository the claim came from, and re-deriving it was chosen over re-reading it.
>
> What this does to [D9](#d9-kores-background-work-runs-in-two-lanes-and-on-kotlinnative-that-costs-two-threads):
> its premise is gone. Two lanes may still be right — a blocking check must still not stall the
> shutdown, and that was *measured* rather than assumed — but "kore must own a thread because the
> platform offers none" is not a reason any more, and the two threads are now a choice that has to
> argue for itself. [B-42](../backlog/B-42-dispatchers-io-exists-on-native.md) owns that.

Written while doing [B-38](../backlog/B-38-native-dispatcher.md), which inherited the claim from
booblik's native module. An inherited finding is a hypothesis until this repository's own toolchain
agrees, so it was checked by compiling a two-line file against the version actually pinned here.

| Fact | Where verified |
|---|---|
| ~~`Dispatchers.IO` does not resolve from `nativeMain` at coroutines 1.11.0~~ — **false**; the probe was missing `import kotlinx.coroutines.IO` | the compiler: *"Cannot access 'val IO: CoroutineDispatcher': it is internal in 'kotlinx.coroutines.Dispatchers'"*, on `:kore-core:compileKotlinLinuxX64` — which is what a missing import of the extension looks like |
| With `import kotlinx.coroutines.IO` it compiles for `linuxX64` and `macosArm64` **and runs** | `:kore-core:compileKotlinMacosArm64`, and `withContext(Dispatchers.IO)` under `linuxX64Test`, 2026-09-12 |
| The version this repository pins is 1.11.0 | `gradle/libs.versions.toml:32` |
| ~~booblik reached the same conclusion independently~~ — it reached the same **mistake**, and had already corrected it before this section was written | `booblik` `08c9bae`, *"docs(native): Dispatchers.IO is not internal on Kotlin/Native"*, 2026-09-11 |

~~**Consequence 1 — the elastic pool is a JVM luxury.**~~ Withdrawn with the fact above: the elastic
pool is on both platforms. What survives is the *shape* of the question — how many threads a service
pays for by depending on kore — but the answer "as many as it must, because the platform offers
none" is not available. B-42.

**Consequence 2 — `Dispatchers.Default` is the wrong answer, not merely a worse one.** It is sized to
the core count and meant for work that does not block; a dependency check that blocks one of its
threads takes a fraction of the process's whole compute capacity with it.


### 1.15 Neither booblik client reconnects, the first consumer had to do it itself, and nothing detects it

Written while doing [B-19](../backlog/B-19-broker-check.md), which existed to build a broker check and
whose stated justification turned out to cite this document for something this document never said.
The claim — "a broker pod replaced, the client dialling nothing again, `EOFException` at five a
second" — was attributed to §1.8's neighbourhood. It is not there. It was checked against the source
instead, and it is **half right in a way worth writing down properly**.

| Fact | Where verified |
|---|---|
| The JVM client dials **once**, in a property initializer, and does not keep the address — so re-dialling is not expressible from inside the object | `booblik/booblik-client/src/main/kotlin/io/github/youndie/booblik/net/client/BooblikConnection.kt:50-57` |
| Its failure path is terminal: `fail()` closes the outbound channel, fails every pending request with `ConnectionClosedException`, closes the socket, and has no path back to a new one | same file, `:173-183` |
| The **native** client has the same shape — `Socket.connect(address)` in an initializer | `booblik/booblik-native/src/nativeMain/kotlin/io/github/youndie/booblik/native/Connection.kt:30-33` |
| There is no `reconnect`, `backoff` or `retry` anywhere in either client's production source | searched across `booblik-client`, `booblik-native`, `booblik-net`, `booblik-protocol`: no hits outside a comment and two lines of doc prose |
| A peer close surfaces as `EOFException("broker closed the connection")`, caught only to fan the failure out to waiting callers | `booblik-client/.../ResponseReader.kt:116`, raised on the reader coroutine at `BooblikConnection.kt:97-108` |
| `Consumer.poll()` does not catch it — the exception leaves the client, so the rate is whatever loop the caller wrote | `booblik-client/.../Consumer.kt:89-97`; the native `records()` is an unguarded `while (true)` at `booblik-native/.../Consumer.kt:135-140` |
| In the first consumer that loop delays 200 ms, which is where "five a second" comes from | [konekt](https://github.com/youndie/konekt) `server/src/main/kotlin/io/konekt/events/UsageConsumer.kt:55`, `:97` |

**The half that is wrong, and it matters.** The first consumer **has already fixed this**, as its
own item `B-107`. `BrokerConnection` is no longer a socket held forever: it holds a generation and a
`reconnect(seen: Int)` guarded by it, so two callers finding the same dead socket replace it once,
and the consumer rebuilds at its saved position.

| Fact | Where verified |
|---|---|
| `reconnect(seen: Int): Int`, synchronized and generation-guarded | konekt `server/src/main/kotlin/io/konekt/events/BrokerConnection.kt:77-92` |
| The consumer reconnects and resumes at its own position rather than from the beginning | konekt `UsageConsumer.kt:105-120` |
| It is tested — replacement is idempotent, the consumer resumes, the outbox recovers | konekt `server/src/test/kotlin/io/konekt/events/BrokerReconnectTest.kt` |

So kore must not describe a permanent wedge in the present tense: the consumer heals. What remains
true is that **the healing is the consumer's own code, because the client offers none** — every
service that talks to booblik writes a generation-guarded reconnect or does without one — and that
the recovery costs a poll interval plus however long the new pod takes to accept.

**Consequence — the detection half is still missing, and that is what kore's check is for.** Nothing
in the first consumer reports broker health: `/health` answers a static string that never touches the
broker (konekt `server/src/main/kotlin/io/konekt/Application.kt:185-189`, and see §1.11 for the same
route serving all three probes). A connect-shaped check would not have helped either, which is the
point of [feature-health-probes](../features/feature-health-probes.md) §3: a socket answers the
kernel. `metadata` is the request that asks the broker, it exists on every client, and the first
consumer already calls it for a different purpose.

| Fact | Where verified |
|---|---|
| `public suspend fun metadata(topics: List<TopicName> = emptyList()): MetadataResult` | `booblik-client/.../BooblikConnection.kt:128` |
| The native client has the blocking equivalent | `booblik-native/.../Connection.kt:75` |
| A named topic that does not exist fails the whole request with `UNKNOWN_TOPIC_OR_PARTITION` — so naming a real topic is the check, not a decoration | the protocol's metadata response; konekt already calls it at `UsageConsumer.kt:151-159` |

**Consequence — a claim's citation is part of the claim.** Two documents in this repository justified
a design decision by pointing at a section that did not contain the finding. The decision survived
verification; the reasoning behind it was improved by it, and one half of it was wrong. That is the
argument for the verification-address column, applied to this repository's own prose.

---

## 2. Decisions

### D1. kore is Kotlin Multiplatform, and Kotlin/Native is the priority target

Brief: unstated — "all your binaries".
Decision: **KMP**, with `linuxX64` and `linuxArm64` as the targets that decide the design, plus
`jvm` and `macosArm64`.

Why:

- the servers the portfolio deploys are Kotlin/Native binaries — tracy, metrik, katcher and shildik
  all run native, for resident sets in the tens of mebibytes against ~100 Mi on the JVM. A library
  that is JVM-first and ported later is a library whose hard cases are discovered last;
- §1.1 makes native the *harder* platform, not merely another one: the engine's ordering is wrong
  there, `expect`/`actual` is needed for the environment, and the signal handling is genuinely
  unsafe. A design that is right on native is right on the JVM by construction; the reverse is not
  true, and every one of §1.1, §1.3 and §1.5 is invisible from the JVM;
- the three agents kore wires already publish exactly `jvm`, `linuxX64`, `linuxArm64` and
  `macosArm64` (§1.6), so matching that set makes the wiring common code rather than four copies;
- the price: `macosArm64` is a development target with a documented hole in it (§1.5), and the
  booblik adapter cannot be common at all (§1.7, D5).

`macosArm64` is in the set so the library builds and its tests run on a laptop, not because anything
is deployed there. Apple mobile targets are out: kore is about a server process, and a phone has
neither a `SIGTERM` nor a readiness probe.

### D2. The stop sequence is named stages with individual deadlines, and kore owns the clock

Decision: `signal → announce → drain → release → exit`, and each stage has a deadline of its own
rather than sharing one budget.

**Amended while implementing (B-04):** *five* was the story and *seven* is the machine. `release`
carries three deadlines, one per group, so the unit that carries a deadline is the group — and the
property that the transcript is a prefix of the specified order needs one unambiguous list.
`KoreStage` therefore has seven entries and the five-stage grouping stays the way it is explained.
The decision below is unchanged; only the count was imprecise.

Why:

- a single overall timeout is a budget the first stage can spend entirely. A dependency check that
  hangs (§1.9 consequence 3) or a flush against a dead broker (§1.8) would otherwise leave nothing
  for the drain, and the symptom is a `SIGKILL` that reads as a crash;
- naming the stages is what makes the order testable. A property test can assert a *sequence of
  recorded stage transitions*; it cannot assert anything about a lambda in `ApplicationStopping`;
- the announce stage — readiness false, then wait — is a stage because §1.10 says the wait is what
  does the work, and a wait with no name is a wait somebody deletes as pointless;
- the price: five numbers to configure instead of one. Mitigated by deriving every default from the
  Kubernetes floor in §1.10 and by refusing, at startup, a set whose sum exceeds the grace period
  kore was told about.

### D3. kore calls `EmbeddedServer.stop` itself and never relies on `addShutdownHook`

Follows from §1.3. The alternative — registering through Ktor's hook — is unavailable on native
without a coin flip about which registration was last, and gives up the ability to run anything
*before* the engine begins stopping, which is the whole of the announce stage.

The price: kore has to install signal handlers, which is platform code on Native and a
`Runtime.addShutdownHook` on the JVM, and it has to cope with the fact that Ktor's own hook is
already installed and cannot be removed. That is a named risk (Risk 2) rather than a solved problem.

### D4. Probes are three routes with three different questions, and only readiness reads dependencies

Decision: `GET /health/startup`, `GET /health/ready`, `GET /health/live`. Startup answers "the
process finished coming up". Ready answers "the process is willing to be sent traffic, and its
declared dependencies answered". Live answers "this process is not wedged" and **never** touches a
dependency.

Why:

- a liveness probe that reads the database restarts a healthy pod during a database blip, and then
  every pod, which is how a dependency outage becomes an outage of everything in front of it;
- readiness is the only one of the three whose failure is cheap: traffic stops, nothing is killed
  (§1.10);
- startup exists to buy time without buying a long liveness delay (§1.10 consequence 4);
- the price: three routes to configure in every chart instead of one, and a genuine possibility of
  getting the chart wrong. kore ships the probe block it expects as documentation in
  [feature-health-probes](../features/feature-health-probes.md) §6, and `--print-config` prints it.

`/health` stays as an alias of `/health/live`, because every chart in the portfolio names it today
and a migration that breaks a running deployment to gain a nicer URL is not worth it.

### D5. The shutdown participant is an interface kore owns *(half withdrawn 2026-09-11, replaced 2026-09-12)*

Decision, and it stands: `kore-core` declares the participant contract and the ordering, and depends
on no broker client at all. A participant is "something that holds a socket and a position and must
be told to stop before the pools close"; booblik consumers are one instance.

**Withdrawn:** the second half, which said `kore-booblik` is a `jvm()`-only module because booblik
cannot be a dependency of native common code. §1.7 carries the correction — `booblik-native` 0.3.3
is published for `linuxX64` and `macosArm64`. The premise was a recorded decision in booblik's
research that a later milestone superseded without amending.

**What replaced it, decided 2026-09-12 ([B-36](../backlog/B-36-booblik-adapter-targets.md)): two
adapters, one per client.** `kore-booblik` is multiplatform with a per-platform actual — the JVM one
over `io.github.youndie.booblik.net.client`, the native one over `io.github.youndie.booblik.native` —
and the common surface is the participant contract above: *flush with a deadline, then close*.

It costs twice the code for a stage no native service uses yet. It is chosen anyway because the one
ordering defect this repository **measured** is native-only — `ApplicationStopping` breaking 48
requests on Kotlin/Native and none on the JVM — so a JVM-only adapter would leave the stage that
matters most untested on the platform where the library's premise was demonstrated. The cheaper
option is cheaper exactly where kore cannot afford it.

And the two clients still differ on the thing the adapter exists for, though not where this document
first said: on a **cancelled scope** the JVM `close()` fails the accumulated batch while the native
one sends it (§1.8). One adapter written against either would be right on the other platform by
accident. Two actuals make the difference explicit, and flush-then-close makes kore independent of
which of the two a service happens to be running — B-45 measured the JVM arm losing 50 of 51 records
to a teardown that a flush saves.

`kore-booblik` is still not in the build: the decision is recorded, the module arrives with
[B-15](../backlog/B-15-booblik-adapter.md).

The one argument that survives intact: not an optional dependency resolved by reflection. Reflection
is not available on Kotlin/Native, and an API whose shape differs per platform is an API whose
documentation is wrong on one of them.

### D6. The oracle asserts the `Connection: close` header, not a closed socket

Deviation from the brief's wording, forced by §1.4. What kore can promise is that a request refused
during the drain carries `503` and `Connection: close`, so a client that honours the header does not
reuse the connection. What it cannot promise on CIO is that the server hangs up. Asserting the
second would produce a test that passes because of the 45-second idle timeout or because of the
grace-period cancellation — in both cases for a reason unrelated to the code under test.

Recorded here rather than silently narrowed, because the next reader will otherwise ask why the
oracle is weaker than the brief.

### D7. Build identity is generated Kotlin source, not a resource

Decision: a Gradle plugin — or, in the first milestone, a plain Gradle task — emits a Kotlin file
with the commit, the build timestamp and the version, and `/version` serves that object.

Why: Kotlin/Native has no JVM-style resource loading, and a `Manifest` does not exist there at all.
A generated source file is the one mechanism that is identical on both platforms. The price is a
generated file in the build directory and a task dependency that is easy to forget to declare —
which is a known way to get a stale value and a green build, so the task's output is an input of the
compilation rather than a side effect.

### D8. `--print-config` prints the resolved configuration with secrets masked, and exits

Decision: a flag on the binary, not an HTTP route.

Why: the question it answers — "what does this deployment think it is configured as" — is asked
before the process serves, often *because* the process will not serve. A route needs a process that
started; a flag works on the image, in a `kubectl run`, in CI, and in the `migrate`-style one-shot
container the portfolio already uses. Secrets are masked by a `secret` marker on the schema field,
so masking is a property of the declaration rather than a list of names somebody maintains.

---


### D9. kore's background work runs in two named lanes, and kore owns no threads *(rewritten 2026-09-12)*

Brief: unstated.

> **This decision was written on 2026-09-12 and rewritten the same day.** Its first version said kore
> owns one thread per lane on Kotlin/Native, because §1.14 said the platform offers nowhere to put
> work that may block. §1.14 was wrong. The measurement that justified *two* lanes survived; the
> argument for *owning threads* did not, and is withdrawn below rather than quietly edited —
> [B-42](../backlog/B-42-dispatchers-io-exists-on-native.md).

**The decision.** kore names two dispatchers — `KoreDispatchers.lifecycle` and
`KoreDispatchers.checks`. The stage machine and the signal watch use the first; dependency checks use
the second. Both are `Dispatchers.IO` on every target, and **kore owns no threads**.

**The cost, stated as a number.** **Zero threads**, on both platforms.

**Why two names for one dispatcher.** Because the reason they are separable outlives the fact that
they are currently equal. The separation is what stops a check blocked on a dead database from
holding the thread the shutdown needs; `Dispatchers.IO` provides it for free by being elastic. The
names remain as the seam a consumer overrides — and pointing `checks` at a dispatcher that cannot
grow is exactly how the hang comes back, which is what `SharedLaneControlTest` now measures.

~~**The cost, stated as a number.** Native: two threads for the life of the process…~~ Withdrawn.
`Dispatchers.IO` is available on Kotlin/Native and is elastic there: with **128 threads blocked for
three seconds, a trivial task was scheduled in 107 µs** (`linuxX64`, coroutines 1.11.0). Two owned
threads would have bought a named entry in a thread dump, at the price of a `close` contract kore
could never honour — a lane outlives every shutdown that might close it.

**Why two rather than one — and this half survived.** A dependency check can block its thread — that
is Risk 3, and `withTimeoutOrNull` stops *waiting* for a blocked call without freeing the thread it
holds. Measured, not assumed: on **one thread**, the sequence takes 2.001 s against a check holding
it for 2 s (`SharedLaneControlTest`, now on both platforms), and on an elastic dispatcher it returns
in milliseconds (`BlockingCheckTest`). What changed is only *who* provides the elasticity.

**Rejected: one lane per check.** It buys freshness for the checks that are not blocked and costs a
thread per dependency. It buys nothing for shutdown, which is already protected. A check stalled
behind another is reported as a stale answer *with its age*, which the registry already does — a
degradation the design states rather than hides.

**Rejected: `Dispatchers.Default`.** It is sized to the core count and meant for work that does not
block; a check blocking one of its threads takes a fraction of the process's whole compute capacity.
This was §1.14's consequence 2 and it is the one part of that section that was never in doubt.

**Rejected: letting the caller decide and documenting nothing.** That is what the code did before,
and the sentence in `ShutdownSequence`'s contract — "returns no later than the sum of the stage
deadlines *plus whatever the caller's own dispatcher makes it wait*" — is exactly the escape hatch
that makes the bound unenforceable. The defaults are overridable; what they are not any more is
unstated.

---

## 3. Risks and open questions

**Risk 1. The ordering is right and nothing proves it stays right.** The whole library is one
promise about a sequence, and a sequence is exactly what a green unit test can fail to cover: a test
that stops a server with nothing in flight passes on an implementation that does the stages in any
order at all. Mitigation, and it is machinery rather than intent: the property test of
[research-oracle](research-oracle.md) §3 asserts over *recorded stage transitions* with randomised
stage durations and randomised failures, and the end-to-end oracle of §2 runs a real `kill -TERM`
under real load. Both are milestone gates, and the property test is proved by mutation — reorder two
stages in the implementation and it must fail.

**Risk 2. Ktor's own shutdown hook is installed by `start()` and cannot be removed.** On Native it
lives in the same global slot kore needs (§1.3), so the two are in a race decided by registration
order. Mitigation: kore registers after `start()` returns and asserts, in a test on a real binary,
that its handler is the one that runs — by observing that the ordered sequence happened, which
Ktor's hook cannot produce. Open: whether a future Ktor version changes the slot to a list, which
would make kore's handler co-resident with one that calls `stop()` directly and reintroduces the
unordered path. Re-check on every Ktor bump; the check belongs in the bump's pull request.

**Risk 3. A dependency check can outlive its deadline and eat the drain.** `withTimeout` does not
interrupt a blocking call, and a native driver's "suspending" call may be blocking underneath.
Mitigation: every dependency check declares its own timeout, the readiness route answers from a
*cached* result refreshed by a background loop rather than by calling the dependency on the request
path, and the loop's overrun is reported as a stale result rather than as a hang. That also makes a
readiness probe cheap enough to run every two seconds, which §1.10 needs.
**Amended 2026-09-12 (B-38): that mitigation covered the request path and left the process.** A
cached result keeps a blocking check off the probe's thread, and says nothing about the thread the
check *is* holding. On Kotlin/Native that thread is one kore owns, and if the shutdown sequence ran
on it the drain would be eaten exactly as this risk says — by the mitigation's own blind side. The
second half is D9: checks run in a lane of their own, and the sequence in one nothing else may
occupy.

**Risk 4. A default that is wrong is worse than no default, because nobody reads it again.** Every
number kore ships — the pre-drain wait, the drain deadline, the release deadline — decides how a
real deployment behaves. Mitigation: each default is derived in a document from the Kubernetes floor
in §1.10 rather than chosen, `--print-config` prints the effective value beside its origin
(`default`, `env`, `code`), and kore refuses at startup when the sum exceeds the grace period it was
told about. **Settled 2026-09-12 ([B-25](../backlog/B-25-undeclared-grace-period.md)):** assume the Kubernetes
default of 30 s, print that it was assumed, and let a service override it — with the direction of the
error named, because it is optimistic. 30 s is the *largest* common budget, so where the real one is
smaller the fit check passes a plan that will be killed: `docker stop` defaults to **10 s**
(measured), against kore's own default deadlines of **29 s**. kore cannot discover the real budget on
any platform it targets, and assuming the smallest would refuse kore's own defaults in the
environment it is aimed at. So the guard is honest about being an assumption, and anyone outside
Kubernetes declares the number.

**Risk 5. Native binaries are the target and the CI that builds them is not free.** Everything in
§1.1, §1.3 and §1.5 is only observable on a native binary under a real signal, which means the gate
needs a Linux runner that builds and runs one. Mitigation: the oracle runs against a container
holding the native sample, and the JVM sample runs the identical scenario so a divergence between
the two is a test failure rather than a discovery. Open: the wall-clock cost of a native link in CI,
which decides whether the oracle runs per pull request or per milestone. Measure in M1, before the
suite is large enough for the answer to be expensive to act on.

**Open question 1. Where the profiler hook belongs.** The brief asks for "a hook for the profiler",
and the portfolio has a real precedent — an AOT-cache training run driven from outside the process.
The hypothesis is that kore should own *enabling* a profiling endpoint or an on-demand dump and
nothing else, because the profilers differ per platform (JFR and async-profiler on the JVM, nothing
equivalent on Kotlin/Native). If that holds, the honest shape is a small extension point plus a JVM
adapter, and on native the hook exists and does nothing — which must be *stated by*
`--print-config`, not discovered.

**Settled 2026-09-12 ([B-29](../backlog/B-29-profiler-hook.md)): dropped, and this is a deviation
from the brief.** Four reasons, and the first two are about kore's own positions rather than about
profilers:

1. **As a route it is incompatible with two decisions already taken.** The route list is closed —
   *"five routes and there will not quietly be a sixth"* — and none of them is authenticated, because
   a deploy check runs before anything has a token (D4, endpoint-kore-admin). A profiling endpoint is
   the one route that **must** be authenticated: it dumps stacks and memory and it is expensive to
   call. Adding it would mean either an unauthenticated dump endpoint or kore growing an auth story.
2. **As a plain hook it adds nothing kore is positioned to add.** Everything else kore wires removes a
   failure mode: a flush that is never called, a close that discards, a stage that runs before the
   drain. Starting a profiler is `-XX:StartFlightRecording` — a deployment flag — and the one
   shutdown-shaped risk, losing the buffer at exit, the JVM already solves declaratively with
   `dumponexit=true`. Checked in the JDK's own help on Java 25, which prints
   `-XX:StartFlightRecording:dumponexit=true` as its example. kore would be duplicating a flag.
3. **On the platform kore is aimed at there is nothing to hook.** Kotlin/Native has no JFR and no
   equivalent, so "the hook exists and does nothing" would be the state on the *primary* target. This
   library exists because a thing that works on the JVM and quietly does nothing on native is the
   shape of bug it was written to prevent — shipping one of its own would be the wrong lesson.
4. **The brief's other four bullets each name a failure this portfolio has had.** This one names a
   capability. That difference is why it was the least defined from the start.

What is **not** dropped is the need it points at: a service that wants a profiler still gets one, from
its deployment, with a flag. kore neither helps nor gets in the way, and that is now written down
rather than left open.

**Open question 2. Whether kore should own the HTTP server at all.** Everything above is written as
"kore mounts routes into your application and wraps your `EmbeddedServer`". The alternative is that
kore *is* the entry point: `koreMain { }` builds the server, installs the routes, reads the config
and runs the sequence. That is more opinionated, removes a class of wiring mistakes, and is closer
to what the brief describes as "one line". It is also how a library stops being a library.
**Settled 2026-09-12 ([B-30](../backlog/B-30-entry-point-question.md)): kore does not own the entry
point.** The hypothesis — the wrapper supported, the pieces public — stands, and the evidence is not
a sample but the shape of the one real service kore targets. konekt's `main()` reads its config, then
**runs migrations and exits without serving**; it chooses its own engine, port and host; and it
installs Koin inside the module before the routes. An entry point owned by kore would need a mode
that starts nothing, a way to be handed a config it knows nothing about, and an ordering hook for
somebody else's DI container — which is "not a config framework", "not a router" and "not DI", in
that order.

The cost is stated rather than hidden: about **eight lines of ceremony** in every consumer around
registrations no API can remove. The answer is not to own `main` but to collapse the stretch kore
*does* own — signal to sequence to release —
[B-46](../backlog/B-46-run-until-signal.md).

What this is short of is the second consumer's *experience*, as opposed to its shape; the item says
what would reopen it.

---

## 4. What happens next

The order of work and the acceptance criteria live in [backlog.md](../../backlog.md). Three things
have to be nailed down before anything else, because every other item rests on them:

1. **The stage machine and its recorded transitions** (M1) — the data structure the property test
   asserts over. Written before any stage does real work, because a sequence retrofitted with
   observability is a sequence whose test was written to match it.
2. **A native sample that really receives `SIGTERM`** (M1) — §1.1, §1.3 and §1.5 are unobservable
   without one, and every fact above that is about Native is a claim this sample turns into a
   measurement.
3. **The oracle, run once against the unfixed shape** (M1) — a negative control. If `kill -TERM`
   under load does not break a service built the ordinary way, the premise of the whole library is
   wrong and that is worth learning in week one rather than at the first release.
