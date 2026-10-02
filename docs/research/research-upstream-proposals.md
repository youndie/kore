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

## 1. Ktor — **deliberately not filed** (decided 2026-09-12)

**The evidence is in.** The negative control ([B-03](../backlog/B-03-negative-control.md),
`measurements-2026-09-11/negative-control.md`) turned §1.1 from a reading of the source into a
result: same source, same configuration, same load, and on Kotlin/Native the idiomatic
`ApplicationStopping` subscriber breaks **48 requests with 5xx on every one of three runs**, while the
JVM breaks none. Both entries below could be defended by whoever filed them.

**They are not being filed.** The owner of this repository decided on 2026-09-12 not to approach
Ktor's tracker ([B-32](../backlog/B-32-file-upstream.md)). That is a decision about where this
portfolio spends its attention, not a doubt about the finding.

**So the entries stay here, and this is now their permanent state rather than a queue.** Section 6
below describes how a proposal is *closed* — by reading the fix in the source and in the published
artefact. These two will not close that way, because nothing has been asked of anyone. What they are
instead is the written record of a difference kore is built around, and it is a public one: this
repository, its research and its measurements are readable by anyone who meets the same behaviour and
searches for it. A finding published in one's own repository is weaker than one in the maintainer's
tracker, and it is not nothing.

**What kore does about it is unchanged and is the point:** it never puts work in
`ApplicationStopping`, and its release stage runs after `EmbeddedServer.stop` has returned, on both
platforms. The workaround does not depend on Ktor agreeing that this is a defect.

**What would reopen it:** somebody else reporting the same thing, or kore being published and its
users meeting it — at which point the question is no longer whether to spend a maintainer's time on
one portfolio's finding.

### 1.1 `EmbeddedServer.stop` runs its steps in the opposite order on JVM and on Kotlin/Native

**Claim.** On JVM, `stop` drains the engine and then destroys the application. On Kotlin/Native it
destroys the application and then drains the engine. `ApplicationStopping` therefore fires after the
drain on one platform and before it on the other, from identical common code, with nothing in the
documentation saying so.

**Verified against** `ktor-server-core` 3.5.2 published sources, re-read in 3.6.0 (#89):
`ktor-server-core-jvm-3.6.0-sources.jar!/jvmMain/io/ktor/server/engine/EmbeddedServerJvm.kt:423-431` against
`ktor-server-core-linuxx64-3.6.0-sources.jar!/posixMain/io/ktor/server/engine/EmbeddedServer.posix.kt:84-94`. See
[research-architecture](research-architecture.md) §1.1 for the full table.

**Why it is worth an issue rather than only a workaround.** The idiom it breaks is the one every
Ktor example teaches — close your resources in `ApplicationStopping` — and on Native it closes them
underneath requests that are still being served. The failure is silent in any test that does not
hold a request open across the stop.

**What kore does meanwhile.** It never puts work in `ApplicationStopping`. Its release stage runs
after `EmbeddedServer.stop` has returned, on both platforms
([feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §3).

~~**Open.**~~ **Settled, in both halves.** This used to say: whether it is a defect or a deliberate
difference is unknown, the first step is a question rather than a bug report, it needs the
measurement attached because "the order differs" is a reading of the source while "a request is
dropped because of it" is a result — and do not file a claim the negative control has not supported.

The measurement arrived and supports it: 48 requests lost on Kotlin/Native, none on the JVM, three
runs each ([B-03](../backlog/B-03-negative-control.md)). **The owner was asked and said not to file**
(2026-09-12). Whether Ktor considers it a defect is therefore a question nobody here will ask, and
the entry stays as the record — see the head of this section.

### 1.2 On Kotlin/Native the shutdown hook is a single global slot, and it runs on the signal stack

**Claim, two halves.** `addShutdownHook` on Native stores the callback in one file-level
`AtomicReference`, so a second caller silently replaces the first — including
`EmbeddedServer.start`'s own hook. And the stored block is invoked from a `staticCFunction` installed
with `signal()`, so it runs arbitrary Kotlin — allocation, locks, `runBlocking` — on the
signal-handler stack, which is not async-signal-safe.

**Verified against** `ktor-server-core-linuxx64-3.6.0-sources.jar!/posixMain/io/ktor/server/engine/ShutdownHook.posix.kt` and
`ktor-server-core-linuxx64-3.6.0-sources.jar!/commonMain/io/ktor/server/engine/ShutdownHook.kt`. Ktor's own KDoc states the
first half plainly, which is a point in its favour and also the reason the second half is worth
raising separately: the replacement behaviour is documented, the signal-safety is not.

**What kore does meanwhile.** It does not use `addShutdownHook` at all: it installs its own handler
with `signal()`, and that handler does nothing but set a flag and wake a parked coroutine
(research D3, and the module reasoning in
[services/kore-library](../services/kore-library.md) §3).

**The signal-safety half is now measured, not argued** ([B-63](../backlog/B-63-sigterm-right-after-start-segfaults.md)).
A `SIGTERM` that meets Ktor's handler — in the window before kore's replaced it — hung the process in
30 of 30 runs with the window widened to a second (main thread parked in `runBlocking` inside the
handler, the workers blocked on a mutex the interrupted code held). `startForKore()` takes the signal before `start`, at `ApplicationStarted`, and
after; the part of `start` before `ApplicationStarted` remains Ktor's.

**And the other half: a Kotlin signal handler is unsafe however little it does**
([B-64](../backlog/B-64-new-worker-thread-segfaults-at-birth.md)). Ktor's handler is a
`staticCFunction`, and so was kore's. A bridge initialises the runtime on the receiving thread. When
that is a worker thread in its first instructions, the worker dies at birth. Reproduced with bare CIO
and a hand-written one-line handler, no kore: 16 crashes in 1 000 early signals, 0 with the handler in
C. The shape a fix would take upstream: install the handler from C, and let Kotlin read a flag.

> **Corrected 2026-09-12 while preparing to file.** This paragraph said `sigaction`. kore used to
> intend that and does not use it: the `sigaction` struct differs between Linux and Darwin, so B-08
> switched to ANSI `signal()` with a handler that only sets a flag (research §1.3 carries the
> correction; `kore-core/src/nativeMain/.../ShutdownSignalWatch.native.kt` is the code). The claim
> about Ktor is unaffected — but this sentence was one paragraph away from being pasted into
> somebody else's tracker.

~~**Open.**~~ **Settled with §1.1: not filed** (2026-09-12). The shape it would have taken is kept
because it is the part that was hard to get right — *"the callback should not run on the signal
stack"*, and explicitly **not** *"the slot should be a list"*. A list would worsen kore's position
rather than improve it: a co-resident hook calling `stop()` directly reintroduces the unordered path
kore exists to remove. Anyone reopening this should start from that distinction rather than rederive
it.

### 1.3 On Kotlin/Native a CIO bind failure aborts the process

**Claim.** CIO binds the listening socket inside a coroutine it launches itself, after
`start(wait = false)` has returned, and nothing handles the bind's failure: on Kotlin/Native a port
another process holds ends the process with `SIGABRT` (exit 134) and an `Uncaught Kotlin exception`
whose cause is `EADDRINUSE`. On the JVM the same start exits 1 with a stack trace. The caller of
`start` has no point at which to catch it on either platform.

**Verified against** `ktor-server-cio-3.6.0-sources.jar!/commonMain/io/ktor/server/cio/backend/HttpServer.kt`
— the `accept-` job's first statement is the `bind`, and the exception handler that file looks up
is installed for the connection scope after the bind, not around it. The abort was measured by a
service built from keel ([keel#49](https://github.com/youndie/keel/issues/49)): 53–59 lines of stack
and exit 134 on native, 18 lines and exit 1 on the JVM.

**What kore does meanwhile.** `requireListenable` binds the port once before the engine does and
refuses as a configuration problem
([B-59](../backlog/B-59-a-busy-port-aborts-the-native-build.md)). It narrows the problem and does not
close it — the port can be taken between the two binds — which is why the entry is here.

**Not filed**, under the same decision as §1.1 and §1.2. The shape it would take: the bind failure
should reach the caller of `start` — through `resolvedConnectors()` or `startSuspend` — rather than a
coroutine root with no handler.

### 1.4 CIO's `reuseAddress = false` is applied on Kotlin/Native and ignored on the JVM

**Claim.** One setting, two behaviours. CIO defaults `reuseAddress` to `false`. `ktor-network` on
native writes that value into the socket; on the JVM it sets the flag only when it is `true`, and the
JDK has already opened the server channel with it on. So a JVM service restarts in place over its own
`TIME_WAIT` and a native one with the identical configuration aborts (§1.3), and nothing a reader of
the setting sees says so.

**Verified against** `ktor-server-cio-jvm-3.6.0-sources.jar!/commonMain/io/ktor/server/cio/CIOApplicationEngine.kt:54`,
`ktor-network-linuxx64-3.6.0-sources.jar!/posixMain/io/ktor/network/sockets/NativeSocketOptions.kt:11-12`,
`ktor-network-jvm-3.6.0-sources.jar!/jvmMain/io/ktor/network/sockets/JavaSocketOptions.kt:84-90`, and
`openjdk-25.0.2 src.zip!/java.base/sun/nio/ch/Net.java` (`serverSocket` opens with `reuse = true`).
Measured on the sample's native binary: 8 server-side `TIME_WAIT`, restart exit 134
([B-62](../backlog/B-62-native-restart-meets-time-wait.md)).

**What kore does meanwhile.** Recommends `reuseAddress = true` on the engine and defaults
`requireListenable` to it (B-62).

**Not filed**, under the same decision. The shape it would take: either apply the JVM's effective
value on native too, or document on the setting that the JVM ignores `false`.

---

## 2. metrik — **filed as [youndie/metrik#29](https://github.com/youndie/metrik/issues/29)**, 2026-09-12

**Claim.** `MetrikAgent.stop()` cancels its job and its scope, closes the sender and closes the
dispatcher, and does not flush the open aggregation window. With the default 60-second window, a
process that stops loses up to a minute of metrics — including everything it served during a drain,
which is the interval an ordered shutdown exists to protect.

Second half: the plugin subscribes the agent to `ApplicationStopping` itself. Given §1.1 that means
the agent stops *before* the drain on Kotlin/Native, so on a native binary the requests served during
shutdown are not measured at all.

**Verified against** `youndie/metrik@577f363!/agent/src/commonMain/kotlin/io/github/youndie/metrik/agent/MetrikAgent.kt:128-134`
and `Metrik.kt:89`, plus `DEFAULT_WINDOW_MS` in the shared protocol.

**Proposed shape.** A bounded final flush in `stop()`, mirroring what tracy's delivery already does
deliberately — and a way for a host to stop the agent itself rather than through the event, so a
library that owns the shutdown order can put it where it belongs.

**What kore does meanwhile.** Nothing it can do from outside; the loss is documented in
[feature-observability-wiring](../features/feature-observability-wiring.md) §7 rather than absorbed.

## 3. tracy — **filed as [youndie/tracy#32](https://github.com/youndie/tracy/issues/32)**, 2026-09-12

**Claim.** `TracyDelivery.stop(grace)` exists, is correct, and is written for exactly this moment —
its own comment says the records produced during a shutdown are the least replaceable ones in the
buffer. Nothing calls it by default: the delivery is a separate object from the plugin, and the
documented wiring is `TracyDelivery(agent, config).start(app)`, which gives the caller no reason to
keep the reference. The portfolio's most complete service does exactly that and loses the last flush
interval on every shutdown.

**Verified against** `youndie/tracy@1054ddd!/agent/src/commonMain/kotlin/io/github/youndie/tracy/agent/TracyDelivery.kt:82-86`
and, for the consequence, `youndie/konekt@0b0a4be!/server/src/main/kotlin/io/konekt/observability/Observability.kt:73`.

**Proposed shape.** Have `start(application)` subscribe `stop` — the delivery already takes the
application, so it can. A correct default beats a correct API nobody calls.

**What kore does meanwhile.** It holds the delivery and calls `stop` in its telemetry group, with its
own deadline ([feature-observability-wiring](../features/feature-observability-wiring.md) §3).

## 4. booblik — **filed as [youndie/booblik#68](https://github.com/youndie/booblik/issues/68)**, 2026-09-12, and it is one line

**Claim.** The JVM and Native clients of the same broker disagree about what `Producer.close()`
does, and the JVM one is the one that loses data.

Both are `mailbox.close()` with a `finally { drainPending() }`. The **native** `drainPending()`
begins with `sendAll()` and then fails only what was still queued in the mailbox. The **JVM**
`drainPending()` has no `sendAll()`: it completes the accumulated batches exceptionally with
`ConnectionClosedException` instead of sending them. So closing a JVM producer without first calling
`flush()` discards up to a linger window of records, silently — and the records that vanish are
exactly the ones nothing was awaiting.

**Verified against** `youndie/booblik@af7788a!/booblik-client/src/main/kotlin/io/github/youndie/booblik/net/client/Producer.kt:228-239`
against `youndie/booblik@af7788a!/booblik-native/src/nativeMain/kotlin/io/github/youndie/booblik/native/Producer.kt:228-242`.

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

## 5. katcher — **CLOSED 2026-09-12**, answered and shipped in client 0.7.47

Asked as [youndie/katcher#50](https://github.com/youndie/katcher/issues/50) and closed the same day.
Kept here as a closed entry rather than deleted, because §6 is a rule about evidence and this is the
first entry that has satisfied it.

**What was asked.** `Katcher` was a global object with `start(configure)` and no `stop` and no
`flush`, so a crash report produced during shutdown might not be uploaded before the process exits.
Whether that mattered depended on *where* katcher wrote and whether that path outlived the pod —
which is a question for its owner rather than an assertion from here.

**The answer.** It is a server scenario: katcher publishes `linuxX64` and `linuxArm64` in every
release, the default directory is `.katcher_cache` relative to the working directory, and nothing
could repoint it. The larger half was that the fatal path had no window at all — the native hook
terminates the process right after it returns, and the JVM handler slept 50 ms against a 3 s connect
timeout. Client 0.7.47 adds three things: `KatcherConfig.cacheDir`, `Katcher.flush(grace)`, and
`KatcherConfig.crashUploadGrace` for the fatal path.

**Read in the source and in the published artefact**, which is what §6 requires: the source in
katcher `fbfedf1`, and `flush`, `cacheDir` and `crashUploadGrace` read out of the jar of
`io.github.youndie.katcher:client-jvm:0.7.47`, downloaded from the portfolio repository and unzipped
— the artefact kore resolves, not a tag. `Duration` is a value class, so they carry mangled names
there (`flush-VtjQ1oo`); a Kotlin consumer never sees that and a Java one cannot call them.

**What kore does now.** `KoreObservability.stop()` calls `flush(flushGrace)` in the telemetry group,
and `KATCHER_CACHE_DIR` points the queue at a volume —
[feature-observability-wiring](../features/feature-observability-wiring.md) §3 and rule 8. The
workaround this entry justified — "nothing to call" in §7 — is deleted in the same change, per §6.

## 7. booblik — `youndie/booblik`, two smaller things found while adapting to it

Neither is a defect in the sense §4 is. Both are things that make an adapter harder to write honestly,
found by writing one ([B-15](../backlog/B-15-booblik-adapter.md)).

### 7.1 `booblik-native` is not published for `linuxArm64`

**Claim.** Central carries `booblik-native-linuxx64` and `booblik-native-macosarm64` and nothing for
`linuxarm64`.

**Verified against** the Central listing `repo1.maven.org/maven2/io/github/youndie/booblik/`, read 2026-09-12.

**Why it matters here.** Research D1 names `linuxX64` **and** `linuxArm64` as the two targets a
server binary in this portfolio actually is. So `kore-booblik` cannot have the second one: the target
is excluded in kore's root build rather than declared and left to fail in somebody's resolution. A
service on arm64 gets the ordered shutdown and not the booblik participant.

**Proposed shape.** Add `linuxArm64` to `booblik-native`'s target set. It is a target addition rather
than a code change — the source is already common.

### 7.2 Central's booblik still carries the pre-migration package

**Claim.** `booblik-client` 0.3.3 on Central contains `ru/workinprogress/booblik/net/client/…`. The
portfolio's move to `io.github.youndie` landed in 0.3.4, which is published to the portfolio's own
repository and **not** to Central.

**Verified against** the published jar in the Gradle cache, 2026-09-12 — not the working tree, which
is ahead of both.

**Why it matters here.** An adapter compiled against Central's 0.3.3 would reference classes the
version a real consumer resolves does not have: a `NoClassDefFoundError` at runtime rather than a
resolution failure at build time. That is what moved `kore-booblik` into the portfolio-only half
(§1.12's amendment).

**Proposed shape.** Publish 0.3.4 to Central. Until then the last version a stranger can use is one
whose package name the project has abandoned.

**What kore does meanwhile.** Pins 0.3.4 from the portfolio's repository and says so where a consumer
meets it.

### 7.3 ~~The broker is not published in any runnable form~~ — **withdrawn, it was wrong**

**This entry claimed booblik publishes no runnable broker. It does.** `release.yml` pushes
`ghcr.io/youndie/booblik:$VERSION` and `:latest` to GHCR on a tag, two of booblik's own workflows
already use that image as a service container, and `docker pull ghcr.io/youndie/booblik:latest`
succeeds anonymously — which is how the claim was finally checked, and how it should have been
checked the first time.

**How it was got wrong.** The probe was `GET https://ghcr.io/v2/youndie/booblik/tags/list`, which
answered `401`, and the `401` was read as *"no image"*. It is not: an anonymous request to the GHCR
registry API is expected to be answered with an authentication challenge whether or not the image
exists. The absence of a token was mistaken for the absence of an artefact — and the one command that
would have settled it, `docker pull`, is also the command every consumer actually uses.

It was caught because the entry was about to be filed as an issue in somebody else's repository, and
filing is what forced re-reading the facts. An entry that had only ever stayed here would have gone on
being believed, and [B-45](../backlog/B-45-booblik-against-a-real-broker.md) would have stayed
"impossible" on a false premise.

## 8. MCP Kotlin SDK — `modelcontextprotocol/kotlin-sdk`, **written and not filed** (2026-10-01)

Not `youndie/*`, so it is asked about first, per the rule at the top. Found extracting `kore-mcp`
([B-68](../backlog/B-68-mcp-endpoint.md)).

**Claim.** On 0.15.0 the stateless streamable-HTTP transport answers a JSON-mode POST with
`call.respond(payload)`, so its JSON-RPC responses are serialised by whatever ContentNegotiation the
*application* installed; the SDK installs its own, with `McpJson`, only when it finds none, and logs a
warning otherwise. `InitializeResult.protocolVersion` is a required field with a default value
(`LATEST_PROTOCOL_VERSION`), so under an application `Json` with `encodeDefaults = false` — kotlinx's own
default — the field is omitted exactly when the negotiated version is the SDK's latest, which is also
what an unknown requested version negotiates to. A client then rejects the initialize result.

**Verified against** `kotlin-sdk-server-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/server/StreamableHttpServerTransport.kt:563`,
`kotlin-sdk-server-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/server/KtorServerHelpers.kt:33-42`,
`kotlin-sdk-core-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/types/initialize.kt:92`,
and by `ProtocolVersionTest`'s tripwire, which asserts the omission on the published 0.15.0.

**Proposed shape.** Either of two, and the first is the one that removes the class rather than the
instance: encode the transport's responses with `McpJson` itself (as its SSE path already does, and as its
`reject` path does with `respondText`), or mark `protocolVersion` `@EncodeDefault` / drop its default.

**What kore does meanwhile.** `kore-mcp` re-encodes the transport's JSON-RPC responses with `McpJson` in
the send pipeline's `Before` phase of the endpoint's own route (`McpMessagesInMcpJson` in
`kore-mcp/src/commonMain/kotlin/io/github/youndie/kore/mcp/KoreMcp.kt`), and the tripwire names the day
to delete it: when the bare SDK keeps the field, the test fails.

---

## 6. How a proposal here is closed

Not by the issue being closed. *Closed* and *fixed* are different claims and only the second is
checkable: an entry leaves this document when the fix is read **in the source and in the published
artefact** of the version kore resolves. The portfolio has one standing example of an issue that is
still open while its fix has shipped, and one of a fix that shipped in a release nobody had bumped
to — both of which read identically from the tracker.

When an entry is closed, the workaround it justified is deleted in the same change. A workaround
whose reason is gone is the thing the next reader inherits and preserves.
