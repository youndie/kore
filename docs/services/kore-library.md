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
> `kore-core` carries the lifecycle package since B-04 and B-11, the signal package since B-08 and
> the health package since B-16; everything else in it is still a path where code will live.
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

**kore is not an entry point** — decided in [B-30](../backlog/B-30-entry-point-question.md) on the
shape of the one real service it targets, whose `main` runs migrations and exits without serving,
picks its own engine, and composes its own DI before any route exists. kore mounts routes into an
application it is handed and wraps an `EmbeddedServer` it is handed.

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
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/` | **built (B-04, B-11)** — `KoreStage` (the specified order), `ShutdownSequence` (the machine), `ShutdownParticipant`, `StagePlan`, `ShutdownTranscript`, and `shutdownSequence { }` with `ShutdownDeadlines` (the assembly a consumer touches) |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/` | **built (B-16, B-09)** — `HealthCheck`, `HealthRegistry`, `HealthStatus.UNKNOWN` as a first-class answer, `ReadinessGate` — the two inputs a probe must not collapse — and `StartupGate` / `LivenessGate` (B-17) |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/BrokerCheck.kt` | **built (B-19)** — the metadata-shaped broker check. A shape rather than a client: research §1.15 has why a connect answers the kernel and not the broker |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/` | **built (B-21–B-24)** — `ConfigKey`, `ConfigSchema`, `ConfigPair`, `Environment`, `EnvironmentNames`, `printConfig`, and the unknown-variable refusal |
| `kore-core/src/linuxMain/kotlin/io/github/youndie/kore/config/Environment.linux.kt` | **built (B-22)** — the `__environ` walk, checked against `/proc/self/environ` |
| `kore-core/src/macosMain/kotlin/io/github/youndie/kore/config/Environment.macos.kt` | **built (B-22)** — the declared gap of research §1.5. Compiles on a Linux host; its tests do not run there, so this branch is unexercised in CI |
| `kore-core/src/jvmMain/kotlin/io/github/youndie/kore/config/Environment.jvm.kt` | `System.getenv()`, which is the whole of it on this target |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/concurrent/KoreDispatchers.kt` | **built (B-38, corrected by B-42)** — the two lanes. Both are `Dispatchers.IO` on every target and kore owns **no** threads; the names are the seam a consumer overrides. Research D9 |
| `kore-core/src/nativeMain/kotlin/io/github/youndie/kore/signal/` | **built (B-08)** — `signal()`, and a handler that writes one integer with a lock-free CAS |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ShutdownRefusal.kt` | **built (B-10)** — the `503` Ktor does not send, and the exemptions that stop it failing the liveness probe |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/EngineDrain.kt` | **built (B-10)** — the drain stage as a participant, calling `stopSuspend` with kore's own numbers |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeRoutes.kt` | **built (B-17)** — the three probes and the `/health` alias |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeBlock.kt` | **built (B-23)** — the chart block `--print-config` prints |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/version/BuildIdentity.kt` | **built (B-26)** — the interface the generated object implements, and `describe`, which is where the `-dirty` suffix is decided once |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/VersionRoute.kt` | **built (B-27)** — `/version`, the body a deploy check greps, and the refusal when the reduction switch would reduce nothing |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/version/Release.kt` | **built (B-27)** — the release value the route reports and the agents will be given, plus the two variables kore reads for itself (`RELEASE`, `KORE_VERSION_REDUCED`) |
| `kore-observability/src/commonMain/kotlin/io/github/youndie/kore/observability/` | **built (B-28)** — `installKoreObservability` and `ObservabilityKeys`. Returns a `ShutdownParticipant` for the telemetry group rather than registering itself, and hands back the tracy agent so a service can build loggers. tracy is `api` here for that reason; metrik and katcher are `implementation` |
| `kore-booblik/src/commonMain/kotlin/io/github/youndie/kore/booblik/` | **built (B-15)** — `FlushThenClose` and `booblikParticipant`: flush with a deadline, then close, with the close in a `finally`. The contract is common; the two adapters are `jvmMain` and `nativeMain`, one per booblik client, sharing no code with each other |
| `kore-booblik/` — **two holes, both deliberate** | **No `linuxArm64`**: `booblik-native` is published for `linuxx64` and `macosarm64` only, so the target is excluded in the root build rather than declared and left to fail in a consumer's resolution. And the module is **portfolio-only**, like `kore-observability`: Central's booblik 0.3.3 still carries the pre-migration `ru.workinprogress` package, so a build against it would compile classes the version a real consumer resolves does not have |
| `kore-build/src/main/kotlin/io/github/youndie/kore/gradle/` | **built (B-26)** — the Gradle plugin that generates the build-identity source. An *included* build, so a consumer of the library gets the plugin without a separate release. The package is `gradle` and not `build`: a Kotlin package with that name is dropped by anything that filters Gradle output by path component, and the symptom is `NO-SOURCE` and a jar with no plugin in it |
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
| Library | `io.github.youndie.tracy:agent` 0.2.15, `io.github.youndie.metrik:agent` 0.2.18, `io.github.youndie.katcher:client` 0.7.47 | `kore-observability` only, and from the **portfolio's own repository** rather than Maven Central (research §1.12, B-37). `implementation`, not `api`: an agent is something kore calls, not something it hands back. The versions are the first consumer's, so adopting kore does not move them — **except katcher**, which is ahead of it on purpose: 0.7.47 is the release that added `flush(grace)`, and 0.7.44 has nothing to call |
| Library | a booblik client | `kore-booblik` only — and there are two of them, one per platform ([B-36](../backlog/B-36-booblik-adapter-targets.md)) |
| Toolchain | Kotlin 2.4.10 / Kotlin/Native | the platform klibs research §1.3 and §1.5 were read from |

Version pinning follows the portfolio's rule: every version is read from the registry's own metadata
before it is written down, not recalled. kore's own catalogue carries the reason above each line.

## 5. Infrastructure and deploy

kore is a library. It publishes artefacts; it deploys nothing.

* **Artefacts:** `io.github.youndie:kore-core`, `-ktor`, `-observability` — targets `jvm`,
  `linuxX64`, `linuxArm64`, `macosArm64` (research D1), 15 artefacts with the metadata modules.
  Publishable since [B-43](../backlog/B-43-publishing-setup.md): `maven-publish` configured in the
  root build rather than by the portfolio's convention plugin, because that one is fetched in
  `pluginManagement` and would make kore unconfigurable without the portfolio's repository. Version
  from `-PVERSION`, defaulting to `0.1.0-SNAPSHOT`. `samples/*` publishes nothing.
  `kore-booblik`'s targets are [B-36](../backlog/B-36-booblik-adapter-targets.md).
* **Resolvable by whom — three modules by anyone, one by the portfolio.** `kore-core`, `kore-ktor`
  and the Gradle plugin resolve from Maven Central alone. **`kore-observability` does not**: the three
  agents it wires are published only to `https://reposilite.kotlin.website/snapshots`, which
  `settings.gradle.kts` declares with a group filter. A consumer outside this portfolio therefore
  gets the ordered shutdown, the three probes, the configuration schema and `/version`, and cannot
  resolve the one module that wires three agents they do not run either.

  Decided in [B-37](../backlog/B-37-agents-not-on-central.md) on 2026-09-12, against the alternative
  of publishing three other projects to Central for kore's sake. It is said here, in the README and in
  research §1.12 because the failure it would otherwise produce is a missing version at resolution
  time, which reads as a broken release rather than as a deliberate boundary.

  The repository is **filtered to `io.github.youndie.*`**, and that is failure isolation rather than
  speed: an unfiltered repository takes part in resolving every dependency, so the day the host is
  unreachable Gradle disables it and fails artefacts it never served — naming the victim instead of
  the cause.
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
