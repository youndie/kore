# kore

One library that does, in every Kotlin server binary, what a Go service gets from its standard
library and from habit: stop in a defined order, answer three different health questions, read its
configuration from the environment against a typed schema, wire its telemetry in one call, and say
which commit it was built from.

**Kotlin Multiplatform, native-first.** `jvm`, `linuxX64`, `linuxArm64`, `macosArm64` — and the
native targets are the ones that decide the design, because every fact that makes this library
necessary is invisible from the JVM.

## Status: the sequence, the probes, the config and `/version` are built

The ordered shutdown, the three probes, the typed configuration and `/version` exist and are
exercised on both the JVM and Kotlin/Native. What is **not** built is the observability wiring and
the booblik participant; the documents that describe them say so, and the backlog says what each
waits on.

This paragraph used to read *"documentation, no code"*. It was true when it was written and stopped
being true without anyone editing it — which is the failure mode a README has that a generated index
does not. Read [backlog.md](backlog.md) for the current count rather than this sentence.

What was established before any of it, and is worth reading before assuming any of this is obvious:

- **Ktor's `EmbeddedServer.stop` runs its steps in the opposite order on JVM and on Kotlin/Native.**
  On JVM it drains the engine and then destroys the application; on Native it destroys the
  application first. So `ApplicationStopping` — where every example tells you to close your pool and
  your broker connection — runs *after* the drain on one platform and *before* it on the other, from
  identical source, with nothing saying so.
- **On Kotlin/Native the shutdown hook is a single global slot**, the last registration wins, and the
  callback runs on the POSIX signal-handler stack, `runBlocking` and all.
- **`Connection: close` on a response does not close a CIO connection** — the engine reads keep-alive
  from the *request's* header. So kore promises the header and not the socket, and says so.
- **There is no `System.getenv()` on Kotlin/Native**, and enumerating the environment — which "fail
  on an unknown variable" needs — exists on Linux as `__environ`, and not at all on macOS.
- **Flipping readiness to false is not what removes a pod from a load balancer**; the control plane
  has already done that. What stops traffic arriving after `SIGTERM` is the interval between the two.

Each of these is sourced to a file and a line in
[docs/research/research-architecture.md](docs/research/research-architecture.md).

## What it does not do

Not a DI container. Not a router. Not a configuration framework with seven sources. Not an
observability library — it wires [tracy](https://github.com/youndie/tracy),
[metrik](https://github.com/youndie/metrik) and [katcher](https://github.com/youndie/katcher) and
reimplements none of them.

## The shape of the promise

```
SIGTERM
  → announce   readiness goes false, then a wait long enough to matter
  → drain      accept stops; in-flight finishes; new arrivals get 503 + Connection: close
  → release    consumers flush then close, then pools, then telemetry — each with its own deadline
  → exit       inside the grace period, on its own
```

The order is the product, and it is asserted twice: by a property test over the stage machine, and by
an end-to-end run that sends a real `SIGTERM` to a real binary under load. Both are written in
[docs/research/research-oracle.md](docs/research/research-oracle.md) — **before** the implementation,
so that the implementation is answerable to something it did not shape.

## What resolves from where

`kore-core`, `kore-ktor` and the Gradle plugin resolve from Maven Central and depend on nothing else.

**`kore-observability` is portfolio-only.** The three agents it wires are not published to Maven
Central, so that one module resolves against
[a private repository](https://reposilite.kotlin.website/snapshots) declared with a group filter in
[settings.gradle.kts](settings.gradle.kts). If you are outside this portfolio you get the ordered
shutdown, the probes, the configuration schema and `/version`, and you cannot build the one module
that wires three agents you do not run either. That is a deliberate boundary rather than an oversight
— the reasoning is [B-37](docs/backlog/B-37-agents-not-on-central.md).

## Documentation

[docs/](docs/) — the map is [docs/README.md](docs/README.md), the backlog is
[backlog.md](backlog.md). Format: [docs-bootstrap](https://github.com/youndie/docs-bootstrap).

```bash
pip install pyyaml
make check
```

## Licence

MIT.
