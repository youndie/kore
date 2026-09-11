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

> **Nothing in this document is built yet.** kore's repository holds documentation and a backlog;
> the module layout below is a decision, not an observation, and the paths in §2a are where the code
> will live rather than where it is. The layer that *is* verified is
> [research-architecture](../research/research-architecture.md) — every fact there was read in an
> artefact that exists. When a module lands, this document is corrected against the code.

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
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/` | the stage machine, the participant contract, the recorded transitions |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/` | the check registry, the cached result, the background refresh loop |
| `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/` | the schema DSL, the reader, the renderer behind `--print-config` |
| `kore-core/src/linuxMain/kotlin/io/github/youndie/kore/config/Environment.linux.kt` | enumeration through `__environ` — the target where the unknown-variable check is possible |
| `kore-core/src/macosMain/kotlin/io/github/youndie/kore/config/Environment.macos.kt` | the honest degradation of research §1.5: lookup works, enumeration does not |
| `kore-core/src/jvmMain/kotlin/io/github/youndie/kore/config/Environment.jvm.kt` | `System.getenv()`, which is the whole of it on this target |
| `kore-core/src/posixMain/kotlin/io/github/youndie/kore/signal/` | `sigaction`, and a handler that only sets a flag |
| `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/` | the probe and version routes, and the wrapper that calls `EmbeddedServer.stop` itself |
| `kore-observability/src/commonMain/kotlin/io/github/youndie/kore/observability/` | tracy, metrik and katcher in one call, with their three different shutdown contracts |
| `kore-booblik/src/main/kotlin/io/github/youndie/kore/booblik/` | the JVM-only adapter of research D5 — flush, then close |
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

**Why `kore-booblik` is a `jvm()` module on its own.** Forced, not chosen: booblik's client is
Kotlin/JVM by its own recorded decision (research §1.7). The alternative — an optional dependency
discovered by reflection — is unavailable on Kotlin/Native at all, and an API that differs in shape
per platform is an API whose documentation is wrong on one of them.

**Why the signal handler does nothing but set a flag.** Research §1.3: Ktor's native handler runs
`runBlocking` on the signal-handler stack, which is not async-signal-safe. kore's handler writes an
atomic and wakes a coroutine that is already parked on an ordinary dispatcher. That is also why the
handler is in `posixMain` rather than in a per-target source set — `sigaction`, `sigemptyset` and
`sigfillset` are present on both Linux targets, verified in the platform klibs.

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
| Library | tracy agent, metrik agent, katcher client | `kore-observability` only |
| Library | booblik client | `kore-booblik` only, and JVM only |
| Toolchain | Kotlin 2.4.10 / Kotlin/Native | the platform klibs research §1.3 and §1.5 were read from |

Version pinning follows the portfolio's rule: every version is read from the registry's own metadata
before it is written down, not recalled. kore's own catalogue carries the reason above each line.

## 5. Infrastructure and deploy

kore is a library. It publishes artefacts; it deploys nothing.

* **Artefacts:** `io.github.youndie:kore-*`, targets `jvm`, `linuxX64`, `linuxArm64`, `macosArm64`
  (research D1). `kore-booblik` is `jvm` only.
* **Gradle plugin:** `io.github.youndie.kore` — generates the build identity of
  [feature-build-identity](../features/feature-build-identity.md).
* **What a consumer deploys** is its own image; what kore contributes to that image is the four
  routes and the sequence. The probe block a chart should carry is in
  [feature-health-probes](../features/feature-health-probes.md) §6.

## 6. Local setup

```bash
./gradlew build
```

Apple targets and anything needing a simulator do not apply — there are none. The native link and
the oracle of [research-oracle](../research/research-oracle.md) §2 need Linux; the Kotlin/Native
Apple-host toolchain cross-compiles the Linux klibs but does not produce a Linux executable, so the
end-to-end run happens on a Linux machine or in a container. Which of the two the gate uses is
[B-06](../backlog/B-06-oracle-harness.md), and the cost of a native link per pull request is
measured before it is decided (research Risk 5).

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
