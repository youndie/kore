---
id: sample-service
title: The sample service — one source, a JVM binary and a native binary
type: service
repo_url: https://github.com/youndie/kore
module: samples
tech_stack: [Kotlin Multiplatform, Kotlin/Native, Ktor CIO]
owner: unassigned
depends_on:
  - kore-library
publishes: []
---

# The sample service

> **Built as of 2026-09-11 (B-05), in its *control* form.** Two binaries from one source, two
> images, both taking `SIGTERM` at PID 1. What is there is the service wired the **ordinary** way —
> one `/health`, one `ApplicationStopping` subscriber — because that is what the negative control of
> [research-oracle](../research/research-oracle.md) §1 has to run first. The load driver and the
> assertions are [B-06](../backlog/B-06-oracle-harness.md); the pooled dependency and the
> configuration schema are not there yet and are named in §4 and §7.

## 1. Responsibility

The sample is **not a demonstration**. It is the fixture the oracle of
[research-oracle](../research/research-oracle.md) §2 runs against, and it exists in two builds from
one source — `jvm` and `linuxX64` — for one reason: research §1.1 says Ktor's stop order is inverted
between the two platforms, and the only way to hold kore to "the order is a specification" is to run
the identical scenario on both and fail the run when they disagree (assertion A7).

It therefore owns exactly what the oracle needs to observe and nothing else:

* a route whose handler takes a configurable time, so requests are certainly in flight when the
  signal arrives;
* one dependency with a pool, so the release stage has something real to close and the readiness
  check has something real to ask;
* one shutdown participant standing in for a message consumer, so the *flush-then-close* of research
  §1.8 is exercised rather than described;
* a configuration schema with a required field, an optional one with a default, and a secret — so
  `--print-config` and the unknown-variable refusal have something to be right about;
* **a resource the stop subscriber closes**, switched on by `--close-on-stop=true`. This is the whole
  of research §1.1 made observable, and without it the control demonstrates nothing about the
  ordering: a subscriber that closes nothing has no consequence whichever side of the drain it runs
  on. Stand-in for a connection pool, which is what services actually close there.

**The sample takes arguments, not environment variables** — `--port`, `--grace`, `--close-on-stop`.
Reading the environment on Kotlin/Native needs an `expect`/`actual` pair, which is
[feature-typed-config](../features/feature-typed-config.md)'s job and does not exist yet; `main(args)`
exists on both targets and needs nothing. When `--grace` is absent the server is left at Ktor's own
default, which is what makes it the control.

What it deliberately does **not** have: a domain, a database schema worth the name, a client, or a
second route that does anything interesting. Every line in the sample is there because an assertion
reads it.

## 2. API contracts

Everything kore mounts — [endpoint-kore-admin](../api/endpoint-kore-admin.md) — plus:

| Method and path | Purpose |
|---|---|
| `GET /work?ms=<n>` | the slow route. `n` defaults to the configured handler delay; the oracle sets it explicitly and prints it with the result. It touches the fragile resource **after** its delay, so the request is still in flight when a stop subscriber runs. |

## 2a. Code anchors

| File | What is there |
|---|---|
| `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/Sample.kt` | **built** — the slow route, `/health`, the stand-in consumer, and the ordinary `ApplicationStopping` wiring the control needs |
| `samples/service/src/jvmMain/kotlin/io/github/youndie/kore/sample/Main.kt` | **built** — the JVM entry point, and the only thing that differs between the builds |
| `samples/service/src/linuxX64Main/kotlin/io/github/youndie/kore/sample/Main.kt` | **built** — the native entry point |
| `samples/service/build.gradle.kts` | **built** — two targets, and the fat jar assembled by hand because `application` does not apply to a multiplatform module |
| `samples/service/Dockerfile` | **built** — two stages, `--target jvm` and `--target native`, both exec form |
| `samples/oracle/` | not built — the load driver and the assertions, [B-06](../backlog/B-06-oracle-harness.md) |

## 3. How it is built

**One source set, two entry points, and the entry points are the only difference.** A sample with a
JVM-shaped `main` and a separately written native one would be two samples, and A7 — "the two
platforms agree" — would be asserting that two different programs behave alike, which is not the
claim.

**The container is the unit of the experiment, not the process.** The oracle sends `SIGTERM` to PID
1, which is how a kubelet does it, and the failure the portfolio has already paid for once is a
shell entrypoint that does not forward signals — a process that never sees the signal looks exactly
like a process that shut down instantly. Research-oracle §2.5 turns that into an assertion (A4)
rather than a thing to remember.

**The JVM variant is the control and the native variant is the subject.** Both run every assertion,
but research §1.1 predicts that the *unfixed* shape fails on native and passes on the JVM — which is
what makes the negative control of research-oracle §1 informative: a JVM-only experiment would have
shown nothing wrong and concluded the library was unnecessary.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Library | `kore-core`, `kore-ktor` | the thing under test |
| Library | `io.ktor:ktor-server-cio` | the engine; CIO because it is what the portfolio's native services run and what research §1.2 was read from |
| Database | a pooled store | **not chosen yet.** It has to exist on both targets, which is a smaller set than it looks, and the choice decides what the readiness check can do — so it belongs with [B-18](../backlog/B-18-pooled-store-check.md), which has to choose one anyway. B-05's acceptance did not need it |

## 5. Infrastructure and deploy

* **Image:** built from `samples/service/Dockerfile`, one tag per variant, not published.
* **Health:** the four routes of [endpoint-kore-admin](../api/endpoint-kore-admin.md).
* **Deployed:** nowhere. The sample runs in CI and on a developer's machine; it is not a service
  anyone operates.

## 6. Local setup

```bash
~/.claude/bin/wsl-run ./gradlew :samples:service:build
cd samples/service && docker build --target jvm -t kore-sample:jvm . && docker build --target native -t kore-sample:native .
```

The binaries are built **outside** Docker and copied in, which is the portfolio's settled shape: a
Gradle and a Kotlin/Native toolchain in the build context on every run is not worth it.

What that produces, measured on 2026-09-11
([the record](../research/measurements-2026-09-11/ci-build.md)):

| | |
|---|---|
| `service-all.jar` | 10.8 MB — assembled by hand; `application` and the Ktor Gradle plugin are `kotlinJvm`-only and do not apply to a multiplatform module |
| `service.kexe` (release) | 3.6 MB, a dynamically linked ELF |
| `kore-sample:jvm` | 493 MB |
| `kore-sample:native` | 47.7 MB |
| `linkReleaseExecutableLinuxX64` | 35.3 s — twenty-five times the debug link |

The oracle run is a separate command, because a scenario that takes a minute and needs Docker is one
that must not be attached to `check` by accident (research Risk 5).

## 7. Configuration

The sample's own prefix is `SAMPLE_`. Its schema is deliberately small and deliberately awkward: one
required string, one integer with a default, one secret, and one field whose name is a near-miss of a
kore key — so the unknown-variable check has a case where being wrong is plausible rather than
obvious.

## 8. Quirks

* **A shell-form `ENTRYPOINT` would make every oracle run pass for the wrong reason.** `/bin/sh -c`
  becomes PID 1 and does not forward `SIGTERM` to its child, so the process never sees the signal,
  the container is killed after the grace period, and the run looks exactly like a process that shut
  down instantly. Both stages use exec form; verified on 2026-09-11 by reading `/proc/<pid>/cmdline`
  of the container's init from the host — `/app/service` for the native image, `java -jar
  /app/service.jar` for the JVM one.
* **`distroless/cc` does not carry `libcrypt.so.1`, and the binary needs it.** Read out of `ldd`
  rather than discovered as a container that will not start. Re-run `ldd` after any dependency
  change; the list is the image's real contract.
* **The two platforms return different exit codes for the same clean shutdown** — `0` on
  Kotlin/Native, `143` on the JVM. Both are correct. This is what corrected oracle assertion A6,
  which used to demand `0` and would have failed every JVM run.
* **The sample's slow route is the only thing keeping the oracle honest, and it is a parameter.**
  Set too low, every assertion still passes and nothing was in flight. That is why research-oracle
  §2.5 counts requests in flight at the signal and fails the run as *inconclusive* below a floor,
  rather than reporting a pass.
* **A sample is the one place where writing the obvious wrong thing is useful.** The negative control
  of research-oracle §1 needs the sample wired the way everybody wires it — one `ApplicationStopping`
  subscriber, one `/health`. That wiring is kept, in a variant of its own, precisely so the
  comparison can be re-run after kore changes. Deleting it once kore works would remove the only
  evidence that kore does anything.
