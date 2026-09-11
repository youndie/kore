# kore

One library that does, in every Kotlin server binary, what a Go service gets from its standard
library and from habit: stop in a defined order, answer three different health questions, read its
configuration from the environment against a typed schema, wire its telemetry in one call, and say
which commit it was built from.

**Kotlin Multiplatform, native-first.** `jvm`, `linuxX64`, `linuxArm64`, `macosArm64` — and the
native targets are the ones that decide the design, because every fact that makes this library
necessary is invisible from the JVM.

## Status: documentation, no code

This repository currently holds research, a backlog and the layer documents. Nothing described in
`docs/features/` exists yet, and every one of those documents says so. What **is** established is
the research, and it is worth reading before assuming any of this is obvious:

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

## Documentation

[docs/](docs/) — the map is [docs/README.md](docs/README.md), the backlog is
[backlog.md](backlog.md). Format: [docs-bootstrap](https://github.com/youndie/docs-bootstrap).

```bash
pip install pyyaml
make check
```

## Licence

MIT.
