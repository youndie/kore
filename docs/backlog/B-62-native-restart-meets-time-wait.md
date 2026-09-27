---
id: B-62
title: "A native restart on the same port meets TIME_WAIT, because CIO listens without SO_REUSEADDR there"
status: done
priority: P2
size: S
stage: m4-config
epic: feature-typed-config
blocked_by: []
---

# B-62 — A native restart on the same port meets TIME_WAIT, because CIO listens without SO_REUSEADDR there

Three native starts of the sample died at bind with `EADDRINUSE` while B-60 was being measured, and
the same thing was measured on keel while it took 0.1.6
([keel#49](https://github.com/youndie/keel/issues/49)). A pod restarts in a fresh network namespace,
so this is mostly a cost for everything that restarts **in place**: a process supervisor, a
developer's loop, an oracle run straight after another, a `docker run --network host`. On native it
is also the abort of B-59, because CIO binds inside its own coroutine.

## What was read, not assumed

| Fact | Where verified |
|---|---|
| CIO's `reuseAddress` defaults to `false` and is handed to the bind | `ktor-server-cio-jvm-3.6.0-sources.jar!/commonMain/io/ktor/server/cio/CIOApplicationEngine.kt:54`, `:131`; `ktor-server-cio-jvm-3.6.0-sources.jar!/commonMain/io/ktor/server/cio/backend/HttpServer.kt:47` |
| On native, `ktor-network` **always** writes the flag: `setSocketFlag(descriptor, SO_REUSEADDR, options.reuseAddress)` — so `false` is an explicit `0` | `ktor-network-linuxx64-3.6.0-sources.jar!/posixMain/io/ktor/network/sockets/NativeSocketOptions.kt:11-12` |
| On the JVM, `ktor-network` sets it only when it is `true`, and otherwise leaves the channel as the JDK made it | `ktor-network-jvm-3.6.0-sources.jar!/jvmMain/io/ktor/network/sockets/JavaSocketOptions.kt:84-90` |
| The JDK makes every server channel with `reuse = true` | `openjdk-25.0.2 src.zip!/java.base/sun/nio/ch/Net.java`, `serverSocket(ProtocolFamily)` → `socket0(…, true, …)` |

So **one configuration listens with `SO_REUSEADDR` on the JVM and without it on Kotlin/Native** —
the shape of research §1.1 again: identical source, and the platforms differ in the one respect
that matters here. Without the flag Linux refuses to bind a port while any `TIME_WAIT` socket holds
it locally, and a server that closed connections first leaves those for about a minute.

## What was measured, and where

- **On keel** (by the keel#49 session, not here): after 20 requests answered with `Connection:
  close`, `ss` showed 20 `TIME_WAIT` on the service port; an immediate restart of the native build
  got `EADDRINUSE`, the JVM distribution restarted on the same port.
- **Here it did not reproduce.** Two back-to-back native oracle runs on one port showed no
  `TIME_WAIT` before the second, which started. Consistent with research §1.4: CIO does not hang up
  on a response's `Connection: close`, so the oracle's client closes first and the `TIME_WAIT` sits
  on the client's side. The three deaths recorded in B-60 are consistent with the mechanism and are
  not proven by it.

## The kernel's rule, measured before anything was written

Linux 6.6 (the build box), Python sockets, a fresh port per cell, the **server** closing first:

| `TIME_WAIT` left by a listener with the flag | new bind without it | new bind with it |
|---|---|---|
| no | refused | **refused** |
| yes | refused | **binds** |

| port something is **listening** on, holder with the flag | new bind without it | new bind with it |
|---|---|---|
| no | refused | refused |
| yes | refused | refused |

Two things follow that the item, as filed, did not know. **The flag has to be on in both processes**:
the kernel checks the one the old socket had, so the first restart after turning it on still meets the
previous process's `TIME_WAIT`. And **a listener is never shared**, flag or not, so B-59's refusal of a
busy port does not weaken.

## Decision: `true`, on the engine and on the check, one value

- `requireListenable` defaults to `reuseAddress = true`. Of the two ways to disagree with the engine,
  `true` here with `false` there lets the check pass over a `TIME_WAIT` the engine cannot bind — the
  abort again; `false` here with `true` there refuses every such restart — the failure the flag was
  set to remove. kore recommends `true` on both, so the default is what a consumer following it passes.
- The sample's kore arm passes `SampleConfig.REUSE_ADDRESS` to both the CIO configuration and
  `requireListenable`. The control arm stays the naïve service.
- Research-upstream-proposals §1.4, written and not filed with the other Ktor entries.
- **Rejected: leaving it to the documentation.** A default nobody changes is the configuration most
  services run, and the difference only shows on the platform kore is designed for.

## Verified

- `ReuseAddressTimeWaitTest` (linuxX64) builds a real server-side `TIME_WAIT` per case: with the flag
  on both sides the check binds; the control — same `TIME_WAIT`, no flag — is refused; a `TIME_WAIT`
  from a listener without the flag is refused even with it; and `requireListenable` with **no
  argument** binds, because every other case passes the flag and would survive the default reverting.
- `ListenCheckTest` gains a port listened on with the flag on both sides: refused, jvm and linuxX64.
- **Mutations**, each killed by the named case: the default back to `false` — only `requireListenable
  binds over the TIME_WAIT by default`, which is why it exists; the native check writing `0`
  regardless — `a restart binds over its own TIME_WAIT…` and the default case.
- **End to end** on the sample's native binary, built from `main` before and from this branch after.
  Eight idle keep-alive clients wait for the server to hang up, so the `TIME_WAIT` is on the server's
  side of the port, then the sample is restarted on the same port at once:

| first run → restart | server-side `TIME_WAIT` | restart |
|---|---|---|
| before → before | 8 | **abort**, exit 134, `EADDRINUSE` |
| after → after (two runs) | 8 | served |
| before → after, the upgrade | 8 | refused, exit 1, `SAMPLE_PORT: … cannot be listened on` |
| busy port, native after | — | refused, exit 1, naming `SAMPLE_PORT` |
| busy port, JVM after | — | refused, exit 1, naming `SAMPLE_PORT` |

The upgrade row is the cost the kernel's rule sets, and the check turns it from the abort into the
one-line refusal. It lasts until the old `TIME_WAIT` expires, about a minute.

- AC: on linuxX64, a native sample restarted on the port of a run that left `TIME_WAIT` on the
  server side starts; a port another process is listening on is still refused by `requireListenable`
  and by the engine, on both targets. Both halves measured, with `ss` output, not inferred. **Met** —
  the tables above; the engine's own refusal of a listened port is the kernel table's second half.
- Anchors: `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/KoreWiring.kt`,
  `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ListenCheck.kt`,
  `kore-ktor/src/linuxTest/kotlin/io/github/youndie/kore/ktor/ReuseAddressTimeWaitTest.kt`
