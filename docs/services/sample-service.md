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

> **Not built yet.** Like [kore-library](kore-library.md), this document describes a decision. It is
> written before the code because the sample is the instrument the library is judged by, and an
> instrument designed after the thing it measures tends to agree with it.

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
  `--print-config` and the unknown-variable refusal have something to be right about.

What it deliberately does **not** have: a domain, a database schema worth the name, a client, or a
second route that does anything interesting. Every line in the sample is there because an assertion
reads it.

## 2. API contracts

Everything kore mounts — [endpoint-kore-admin](../api/endpoint-kore-admin.md) — plus:

| Method and path | Purpose |
|---|---|
| `GET /work?ms=<n>` | the slow route. `n` defaults to the configured handler delay; the oracle sets it explicitly and prints it with the result. |

## 2a. Code anchors

| File | What is there |
|---|---|
| `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/Main.kt` | the whole sample: schema, wiring, the slow route |
| `samples/service/src/jvmMain/kotlin/` | the JVM entry point |
| `samples/service/src/linuxX64Main/kotlin/` | the native entry point |
| `samples/service/Dockerfile` | the container the oracle runs, for both variants |
| `samples/oracle/` | the load driver and the assertions of research-oracle §2.3 |

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
| Database | a pooled store | so the release stage closes something real. Which one is [B-05](../backlog/B-05-sample-service.md) — it has to exist on both targets, which is a smaller set than it looks |

## 5. Infrastructure and deploy

* **Image:** built from `samples/service/Dockerfile`, one tag per variant, not published.
* **Health:** the four routes of [endpoint-kore-admin](../api/endpoint-kore-admin.md).
* **Deployed:** nowhere. The sample runs in CI and on a developer's machine; it is not a service
  anyone operates.

## 6. Local setup

```bash
./gradlew :samples:service:runJvm
```

The native variant needs a Linux host or a container — see [kore-library](kore-library.md) §6. The
oracle run is a separate command, because a scenario that takes a minute and needs Docker is one that
must not be attached to `check` by accident (research Risk 5).

## 7. Configuration

The sample's own prefix is `SAMPLE_`. Its schema is deliberately small and deliberately awkward: one
required string, one integer with a default, one secret, and one field whose name is a near-miss of a
kore key — so the unknown-variable check has a case where being wrong is plausible rather than
obvious.

## 8. Quirks

* **The sample's slow route is the only thing keeping the oracle honest, and it is a parameter.**
  Set too low, every assertion still passes and nothing was in flight. That is why research-oracle
  §2.5 counts requests in flight at the signal and fails the run as *inconclusive* below a floor,
  rather than reporting a pass.
* **A sample is the one place where writing the obvious wrong thing is useful.** The negative control
  of research-oracle §1 needs the sample wired the way everybody wires it — one `ApplicationStopping`
  subscriber, one `/health`. That wiring is kept, in a variant of its own, precisely so the
  comparison can be re-run after kore changes. Deleting it once kore works would remove the only
  evidence that kore does anything.
