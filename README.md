# kore

[![kore-core](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/kore-core?name=kore-core&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/kore-core)
[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![native](https://img.shields.io/badge/Native-blue?logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-25-orange?logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

**One library that gives a Kotlin server binary what a Go one gets from the standard library and
from habit:** stop in a defined order, answer three different health questions, read its
configuration from the environment against a typed schema, wire its telemetry in one call, and say
which commit it was built from.

**Kotlin Multiplatform, native-first.** `jvm`, `linuxX64`, `linuxArm64`, `macosArm64` — and the
native targets decide the design, because every fact that makes this library necessary is invisible
from the JVM.

> **Status: all five features built, `0.1.3` published, first consumer mid-adoption.**
>
> 51 backlog items closed, 158 tests on `aarch64` and a suite on Apple silicon per pull request, an
> end-to-end oracle that sends a real `SIGTERM` to a real container under load.
>
> What that does not cover, said out loud because a status line that only lists wins is not a status
> line: **nothing is on Maven Central yet**, so an outside consumer resolves from the portfolio's
> repository; the pre-drain default of five seconds is a hypothesis carrying the item that will
> settle it; and the first real adoption found four defects in a day that none of the above caught —
> a published example that cannot run on one platform among them.

## The shape of the promise

```
SIGTERM
  → announce   readiness goes false, then a wait long enough to matter
  → drain      accept stops; in-flight finishes; new arrivals get 503 + Connection: close
  → release    consumers flush then close, then pools, then telemetry — each with its own deadline
  → exit       inside the grace period, on its own
```

**The order is the product.** It is asserted twice: by a property test over the stage machine, and by
an end-to-end run that signals a real binary under load. Both were written in
[docs/research/research-oracle.md](docs/research/research-oracle.md) **before** the implementation,
so that the implementation is answerable to something it did not shape.

## What a consumer writes

```kotlin
server.start(wait = false)     // not `wait = true` — the main thread has to reach the await
startup.markStarted()

runBlocking {
    runUntilSignal(
        deadlines,
        // Inside, not after: on the JVM this call returning means the shutdown hook has
        // returned and the process is already on its way out.
        onFinished = { run -> println(run.transcript) },
    ) {
        announce(AnnounceNotReady(readiness))
        drain(EngineDrain(server, deadlines.drain, deadlines.drain + 5.seconds))
        consumer(booblikParticipant("events", producer))
        pool(myPool)
    }
}
```

Both snippets are compiled by
[`samples/readme`](samples/readme/src/main/kotlin/io/github/youndie/kore/readme/ReadmeExamples.kt), so
`./gradlew build` fails when one of them stops typing. That is narrower than "the example works" — it
catches a signature that moved, not a placement that races — and it exists because this project
published an example that could not run on one of its two platforms, and a consumer found it rather
than a test.

kore does **not** own `main`. A real entry point runs migrations, chooses its engine and composes its
own DI before any route exists; owning that would make this a framework. What it owns is the stretch
from the signal to the exit — and each of those three steps is one a consumer gets wrong in a way
that looks like it works.

The probes, the schema and `/version` are one call each:

```kotlin
installKoreProbes(startup, readiness, liveness)   // /health/startup, /health/ready, /health/live
installKoreVersion(KoreBuildIdentity)             // /version, compiled in by the Gradle plugin
installKoreObservability(settings)                // tracy, metrik, katcher — or none, which is valid

val config = ConfigSchema("MYAPP", keys = myKeys + ObservabilityKeys.all).read(systemEnvironment())
```

## What it does not do

Not a DI container. Not a router. Not a configuration framework with seven sources. Not an
observability library — it wires [tracy](https://github.com/youndie/tracy),
[metrik](https://github.com/youndie/metrik) and [katcher](https://github.com/youndie/katcher) and
reimplements none of them.

## Five facts that decided the design

Each carries a file and a line in
[docs/research/research-architecture.md](docs/research/research-architecture.md), and each
contradicts what a Ktor example would lead you to write.

- **`EmbeddedServer.stop` runs its steps in the opposite order on JVM and on Kotlin/Native.** So
  `ApplicationStopping` — where every example tells you to close your pool and your broker
  connection — runs *after* the drain on one platform and *before* it on the other, from identical
  source, with nothing saying so. This is why the library exists.
- **On Kotlin/Native the shutdown hook is a single global slot**, last registration wins, and the
  callback runs on the POSIX signal-handler stack, `runBlocking` and all.
- **`Connection: close` on a response does not close a CIO connection** — the engine reads keep-alive
  from the *request's* header. kore promises the header and not the socket, and says so.
- **There is no `System.getenv()` on Kotlin/Native**, and enumerating the environment — which "fail
  on an unknown variable" needs — exists on Linux as `__environ`, and not at all on macOS.
- **Flipping readiness to false is not what removes a pod from a load balancer**; the control plane
  has already done that. What stops traffic arriving after `SIGTERM` is the interval between the two.

## What it costs

Measured 2026-09-12, not estimated: `samples/service` as one binary with two arms selected by
`--kore=true`, alternating, fifteen repetitions per cell plus a discarded warm-up. Median.

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
changed sign twice.

And the thing the release stage exists for, measured against a real broker: a producer closed and
torn down in the same breath reads back **1 of 51** records, the same producer through kore's
participant **51 of 51**.

Re-measure rather than trust the table — a number in a README has no way to go stale visibly:

```bash
./gradlew :samples:oracle:measure --args="--repeats=15 --work=3000 --connections=8"
./gradlew :samples:oracle:brokerFlush
```

Method, raw output and the four things this harness got wrong first:
[three-numbers.md](docs/research/measurements-2026-09-12/three-numbers.md),
[broker-flush.md](docs/research/measurements-2026-09-12/broker-flush.md).

## Install

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots") {
        content { includeGroupByRegex("io\\.github\\.youndie.*") }
    }
}

dependencies {
    implementation("io.github.youndie:kore-core:0.1.3")
    implementation("io.github.youndie:kore-ktor:0.1.3")        // probes, /version, drain, 503 refusal
    implementation("io.github.youndie:kore-booblik:0.1.3")     // flush-then-close for a booblik producer
    implementation("io.github.youndie:kore-observability:0.1.3")
}

plugins {
    id("io.github.youndie.kore.build") version "0.1.3"          // what /version reports
}
```

**Nothing is on Maven Central yet**, and this section used to say that three of these resolved from
there. They do not, and had not when it was written — a sentence about a plan in the tense of a fact,
in the one place a stranger reads to decide whether they can use this. Central is still where the
dependency-free modules belong; [B-37](docs/backlog/B-37-agents-not-on-central.md) says which.

**`kore-observability` is portfolio-only by construction.** The three agents it wires are not on
Central either, so that one module cannot resolve outside this portfolio. If you are outside it you
get the ordered shutdown, the probes, the configuration schema and `/version`, and you cannot build
the one module that wires three agents you do not run. That is a deliberate boundary rather than an
oversight.

## Documentation

[docs/](docs/) — the map is [docs/README.md](docs/README.md), the backlog is
[backlog.md](backlog.md). Format: [docs-bootstrap](https://github.com/youndie/docs-bootstrap).

```bash
pip install pyyaml
make check
```

## Licence

MIT.
