# kore

One library that does, in every Kotlin server binary, what a Go service gets from its standard
library and from habit: stop in a defined order, answer three different health questions, read its
configuration from the environment against a typed schema, wire its telemetry in one call, and say
which commit it was built from.

**Kotlin Multiplatform, native-first.** `jvm`, `linuxX64`, `linuxArm64`, `macosArm64` — and the
native targets are the ones that decide the design, because every fact that makes this library
necessary is invisible from the JVM.

## Status: all five features are built, and `0.1.0` is published

The ordered shutdown, the three probes, the typed configuration, `/version` and the observability
wiring all exist and are exercised on both the JVM and Kotlin/Native, as is the booblik participant.

This paragraph has now been wrong twice — it read *"documentation, no code"* after the code arrived,
and *"the observability wiring is not built"* after that was built too. Both were true when written
and stopped being true with nobody editing them, which is the failure mode a README has and a
generated index does not. Read [backlog.md](backlog.md) for the current count rather than this
sentence.

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

## What it costs

Measured on 2026-09-12, not estimated: `samples/service` as one binary with two arms selected by
`--kore=true`, alternating, fifteen repetitions per cell plus a warm-up that is discarded and printed
as discarded. Median.

| | time to first `/health` | RSS at ready | stop under load |
|---|---|---|---|
| JVM, without kore | 1171 ms | 111 180 kB | 1491 ms |
| JVM, with kore | 1201 ms (*noise*) | 116 788 kB (**+5 %**) | 3498 ms (**+135 %**) |
| native, without kore | 727 ms | 25 920 kB | 1523 ms |
| native, with kore | 717 ms (*noise*) | 27 520 kB (**+6 %**) | 3499 ms (**+130 %**) |

**The third column is not a cost.** In those same runs, of every request in flight when the signal
arrived, the arm without kore finished **none and dropped all of them** — both platforms, all fifteen
rounds — and the arm with kore finished all of them. The two seconds are what the work costs when it
is not thrown away, and a reader shown only that column would read the sign backwards.

**The first column is noise and is printed as noise.** Three runs of this harness put the JVM startup
difference at +12 ms, −23 ms and +30 ms — the sign flips in both directions, against a within-arm
spread of 250 ms. An earlier version of this table printed that column as **+1 %**, which read as a
measured cost and was an artefact of which afternoon the run happened on.

So the only cost that survives more samples is **RSS: +1.6 MB on native, +5.6 MB on the JVM** — a
band a few hundred kilobytes wide across all three runs, holding its sign while the startup column
changed sign twice. Both the megabytes and the percentage are here because neither decides anything
alone.

And the thing the release stage exists for, measured against a real broker: a producer closed and torn
down in the same breath reads back **1 of 51** records, the same producer through kore's participant
**51 of 51**.

Re-measure rather than trust the table — a number in a README has no way to go stale visibly:

```bash
./gradlew :samples:oracle:measure --args="--repeats=5 --work=3000 --connections=8"
./gradlew :samples:oracle:brokerFlush
```

Method, raw output and the three things this harness got wrong first:
[three-numbers.md](docs/research/measurements-2026-09-12/three-numbers.md),
[broker-flush.md](docs/research/measurements-2026-09-12/broker-flush.md).

## What resolves from where

**Everything resolves from [the portfolio's repository](https://reposilite.kotlin.website/snapshots)
today, and nothing is on Maven Central yet.** This section used to say that `kore-core`, `kore-ktor`
and the Gradle plugin resolved from Central. They do not, and had not when it was written — a
sentence about a plan, in the tense of a fact, in the one place a stranger reads to find out whether
they can use this. Reported from the first consumer as
[#58](https://github.com/youndie/kore/issues/58). Central is still where the three
dependency-free modules belong, and it is [B-37](docs/backlog/B-37-agents-not-on-central.md) that
says which ones can go.

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots") {
        content { includeGroupByRegex("io\\.github\\.youndie.*") }
    }
}
```

| | |
|---|---|
| `kore-core`, `kore-ktor`, `kore-booblik` | depend on nothing outside Kotlin, Ktor and coroutines |
| `kore-build` — the Gradle plugin `/version` needs | published since #58; the identity it compiles in cannot be produced any other way |
| `kore-observability` | **portfolio-only by construction** — see below |

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
