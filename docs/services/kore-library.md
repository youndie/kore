---
id: kore-library
title: kore — the library and its modules
type: service
repo_url: https://github.com/youndie/kore
tech_stack: [Kotlin Multiplatform, Kotlin/Native, Ktor, Gradle]
owner: unassigned
depends_on:
  - ktor-server-core
  - kotlinx-coroutines
publishes:
  - "io.github.youndie:kore-core"
  - "io.github.youndie:kore-ktor"
  - "io.github.youndie:kore-observability"
  - "io.github.youndie:kore-booblik"
  - "io.github.youndie:kore-build (Gradle plugin)"
---

# kore — the library and its modules

> **Partly built as of 2026-09-11 (B-01).** Three modules exist and compile on all four targets:
> `kore-core`, `kore-ktor`, `kore-observability`. They are skeletons — only `kore-core` carries any
> source. Two rows of §2a are **deliberately not built**, and the reason in each case is a decision
> whose premise changed:
>
> * `kore-booblik` is absent — [B-36](../backlog/B-36-booblik-adapter-targets.md);
> * `kore-observability` has no agent dependencies — [B-37](../backlog/B-37-agents-not-on-central.md);
> * `kore-build` and `samples/` belong to B-26 and B-05 and were never B-01's.
>
> `kore-core` carries the lifecycle package since B-04 and the signal package since B-08; everything
> else in it is still a path where code will live.
>
> Everything else in this document is still a decision rather than an observation.

## 1. Responsibility

kore owns the **process lifecycle** of a Kotlin server binary: the order in which it starts serving,
the order in which it stops, the three probes that report the first, and the configuration and build
identity that make a deployment answerable for what it is running.

What it deliberately does **not** do:

- **dependency injection.** kore takes participants; it does not build an object graph. The
  portfolio uses Koin and `ktor-server-di` and kore must work under both and under neither;
- **routing.** kore mounts four routes of its own and touches nothing else in the routing tree;
- **configuration with seven sources.** One source: the environment. No files, no remote config, no
  precedence rules. The value of the feature is the *schema* and the refusal, not the plumbing
  (see [feature-typed-config](../features/feature-typed-config.md) §6);
- **observability.** kore wires the three agents; the agents are tracy, metrik and katcher and kore
  reimplements none of them;
- **anything a phone does.** Apple mobile targets are out of scope by D1 of the research: kore is
  about a process that receives `SIGTERM`.

## 2. API contracts

kore's public surface is three things, and every one of them is part of the contract rather than an
implementation detail:

* **The HTTP routes** — the complete reference is
  [endpoint-kore-admin](../api/endpoint-kore-admin.md). Four routes, no authentication, and that is
  a decision with a reason recorded there.
* **The stage sequence** — [feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §3.
  The names of the stages and their order are the specification; the property test of
  [research-oracle](../research/research-oracle.md) §3 is what keeps them one thing.
* **The configuration schema DSL** — [feature-typed-config](../features/feature-typed-config.md).
  A consumer declares fields; kore reads, validates, prints and refuses.

## 2a. Code anchors

Where each concern will live. One module per reason to depend on something.

| File | What is there |
|---|---|
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/` | **built (B-04)** — `KoreStage` (the specified order), `ShutdownSequence` (the machine), `ShutdownParticipant`, `StagePlan`, `ShutdownTranscript` |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/` | the check registry, the cached result, the background refresh loop |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/` | the schema DSL, the reader, the renderer behind `--print-config` |
| `kore-core/src/linuxMain/kotlin/io/github/youndie/kore/config/Environment.linux.kt` | enumeration through `__environ` — the target where the unknown-variable check is possible |
| `kore-core/src/macosMain/kotlin/io/github/youndie/kore/config/Environment.macos.kt` | the honest degradation of research §1.5: lookup works, enumeration does not |
| `kore-core/src/jvmMain/kotlin/io/github/youndie/kore/config/Environment.jvm.kt` | `System.getenv()`, which is the whole of it on this target |
| `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/signal/` | **built (B-08)** — `signal()`, and a handler that writes one integer with a lock-free CAS |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ShutdownRefusal.kt` | **built (B-10)** — the `503` Ktor does not send, and the exemptions that stop it failing the liveness probe |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/EngineDrain.kt` | **built (B-10)** — the drain stage as a participant, calling `stopSuspend` with kore's own numbers |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/` | the probe and version routes — B-17 |
| `kore-observability/src/commonMain/kotlin/io/github/youndie/kore/observability/` | tracy, metrik and katcher in one call, with their three different shutdown contracts |
| `kore-booblik/` | **not built** — flush-then-close for booblik. Its target set is [B-36](../backlog/B-36-booblik-adapter-targets.md); D5's "JVM only" was withdrawn when `booblik-native` turned up on Central |
| `kore-build/src/main/kotlin/io/github/youndie/kore/build/` | the Gradle plugin that generates the build-identity source |
| `samples/` | the two sample binaries — [sample-service](sample-service.md) |

## 3. How it is built

The mechanics that decide the module boundaries, and none of them is a matter of taste:

**Why `kore-ktor` is separate from `kore-core`.** The stage machine has no idea what an HTTP server
is, and that is what makes it testable by the property test of
[research-oracle](../research/research-oracle.md) §3 — a machine driven directly, with no socket, no
port and no engine. A stage machine reachable only through a running server is one whose orderings
can only be tested by scripting them, which is the class of test this library exists to distrust.

**Why `kore-observability` is separate again.** A service that wants the ordered shutdown and does
not send anything to tracy should not resolve three agent artefacts to get it. The three shutdown
contracts of research §1.6 live here and nowhere else.

**Why `kore-booblik` is a module on its own, and why it does not exist yet.** Separate because
`kore-core` must not depend on a broker client at all — a participant is a contract, not a booblik
type. Absent because the decision that said "and it is JVM-only" rested on a premise that turned out
to be false: there are two published booblik clients with two different APIs, not one
(research §1.7). Which targets the adapter has is [B-36](../backlog/B-36-booblik-adapter-targets.md),
and a module written to a superseded decision is worse than a module that is not there yet. The one
argument that survives: not an optional dependency discovered by reflection — unavailable on
Kotlin/Native, and an API that differs in shape per platform is one whose documentation is wrong on
one of them.

**Why the signal handler does nothing but set a flag.** Research §1.3: Ktor's native handler runs
`runBlocking` on the signal-handler stack, which is not async-signal-safe. kore's handler writes one
integer with a lock-free compare-and-set, and an ordinary coroutine notices.

It lives in `nativeMain` and uses `signal()` rather than `sigaction()`. That is portability, not the
interesting choice: `struct sigaction` differs between Linux and Darwin, so `sigaction` would mean two
implementations of a handler that writes one integer. **The waiting half is what genuinely cannot be
shared** — on Native the handler returns and `main` carries on, while on the JVM the hook thread *is*
the shutdown and must not return until the sequence is done. That asymmetry is the reason this is an
`expect`/`actual` pair at all.

**Why kore calls `EmbeddedServer.stop` rather than registering through `addShutdownHook`.**
Research D3. On Kotlin/Native the hook is a single global slot and the last registration wins;
registering there means either replacing Ktor's own hook or being replaced by it, decided by an
ordering nobody writes down. Calling `stop` directly also buys the thing the announce stage needs:
somewhere to run *before* the engine begins stopping, which no Ktor event offers (research §1.2).

**Why the version is generated source and not a resource.** Research D7: Kotlin/Native has no
JVM-style resources and no manifest. The generated file is an input of the compilation rather than a
side effect of the build, because the failure mode of getting that wrong is a stale commit hash and
a green build.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Library | `io.ktor:ktor-server-core` | the application, the events, `EmbeddedServer` — `kore-ktor` only |
| Library | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | the stage machine and the health refresh loop |
| Library | tracy agent, metrik agent, katcher client | `kore-observability` only — **not declared yet**: none of the three is on Maven Central (research §1.12), which is [B-37](../backlog/B-37-agents-not-on-central.md) |
| Library | a booblik client | `kore-booblik` only — and there are two of them, one per platform ([B-36](../backlog/B-36-booblik-adapter-targets.md)) |
| Toolchain | Kotlin 2.4.10 / Kotlin/Native | the platform klibs research §1.3 and §1.5 were read from |

Version pinning follows the portfolio's rule: every version is read from the registry's own metadata
before it is written down, not recalled. kore's own catalogue carries the reason above each line.

## 5. Infrastructure and deploy

kore is a library. It publishes artefacts; it deploys nothing.

* **Artefacts:** `io.github.youndie:kore-*`, targets `jvm`, `linuxX64`, `linuxArm64`, `macosArm64`
  (research D1). Verified by building them on 2026-09-11: all three native klibs and the JVM jar are
  produced, and the `macosArm64` klib cross-compiles on a Linux host. `kore-booblik`'s targets are
  [B-36](../backlog/B-36-booblik-adapter-targets.md).
* **Resolvable by whom:** everything kore depends on today is on Maven Central. That is a property
  worth keeping and it is the whole content of [B-37](../backlog/B-37-agents-not-on-central.md).
* **Gradle plugin:** `io.github.youndie.kore` — generates the build identity of
  [feature-build-identity](../features/feature-build-identity.md).
* **What a consumer deploys** is its own image; what kore contributes to that image is the four
  routes and the sequence. The probe block a chart should carry is in
  [feature-health-probes](../features/feature-health-probes.md) §6.

## 6. Local setup

```bash
~/.claude/bin/wsl-run ./gradlew build
```

**The build runs on the Linux box, not on the Mac** — the repository is a mutagen session, and the
wrapper flushes it and goes over ssh. Editing and `git` stay on the Mac: the replica is one-way, so
work done there is reverted and a diff taken there proves nothing.

What a green `build` covers, measured on 2026-09-11 rather than assumed:

| On the Linux box | |
|---|---|
| `compileKotlinJvm`, `compileKotlinLinuxX64`, `compileKotlinLinuxArm64`, `compileKotlinMacosArm64` | **run** — all four produce artefacts, the Apple klib included |
| `jvmTest`, `linuxX64Test` | **run** — result XML written |
| `linuxArm64Test`, `macosArm64Test` | **SKIPPED**, inside `BUILD SUCCESSFUL` — a host cannot run what it cannot execute |

So a green build means the `linuxArm64` and `macosArm64` code *compiles* and says nothing about any
test there. Anything that must be true on those targets needs a test that runs somewhere they run,
or it is not covered.

The oracle of [research-oracle](../research/research-oracle.md) §2 needs a Linux *executable*, which
is a link rather than a klib; which machine the gate uses is
[B-06](../backlog/B-06-oracle-harness.md), and the cost of a native link per pull request is measured
before it is decided (research Risk 5).

## 7. Configuration

kore reads its own settings under the `KORE_` prefix, through the same schema mechanism it offers
consumers — so kore's own configuration is the first test of
[feature-typed-config](../features/feature-typed-config.md). The keys are listed once, in that
document's §4, and not copied here.

A service's *own* prefix is declared by its schema and is never `KORE_`.

## 8. Quirks

Nothing to record yet: there is no code. When there is, this section is the first place a surprise
goes — and the two already known from reading other people's code are not kore's own and live where
they were found:

- a `503` from kore carries `Connection: close` and the CIO server still does not hang up
  (research §1.4) — a consumer that expects the socket to close is expecting something Ktor does not
  offer;
- on Kotlin/Native, anything else in the process that calls `EmbeddedServer.addShutdownHook` silently
  takes the single global slot (research §1.3). kore registers after `start()` and therefore wins
  today; a library added later that registers after kore would take it back, and nothing would say
  so. That is Risk 2 of the research, and the reason the oracle asserts the *sequence* rather than
  the exit code alone.
