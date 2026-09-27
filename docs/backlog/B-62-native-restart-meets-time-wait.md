---
id: B-62
title: "A native restart on the same port meets TIME_WAIT, because CIO listens without SO_REUSEADDR there"
status: open
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

## The question

Should kore make `reuseAddress = true` the default it recommends, on both platforms?

- **For:** it makes native behave as the JVM already does, and removes a restart failure that is
  also an abort. `SO_REUSEADDR` on Linux does **not** let a second process take a port something is
  *listening* on — that is `SO_REUSEPORT` — so B-59's refusal of a busy port should survive. **To be
  checked, not assumed** — keel took `reuseAddress = true` on 2026-09-27 and said it would check it
  there; the AC below asks for it to be measured here regardless.
- **Against:** it is a Ktor default kore would be overriding, and the case it fixes is mostly
  outside Kubernetes.

## What changes if the answer is yes

1. The sample sets `reuseAddress = true` in the engine configuration **and** passes the same flag to
   `requireListenable` — the check has to bind the way the engine will, or it answers a different
   question (B-59, "fails where CIO would and with the same option").
2. The feature and service documents say it where the engine configuration is shown, with the
   reason and the table above.
3. Whether `requireListenable`'s own default flips to `true` is part of the decision: a default that
   disagrees with the engine's is the easier mistake of the two.
4. The upstream half is Ktor's and belongs in research-upstream-proposals as an entry written and
   not filed, like the other Ktor ones — **not written yet**: CIO's `false` is applied literally on
   native and ignored on the JVM, which no reader of the one setting can know.

- AC: on linuxX64, a native sample restarted on the port of a run that left `TIME_WAIT` on the
  server side starts; a port another process is listening on is still refused by `requireListenable`
  and by the engine, on both targets. Both halves measured, with `ss` output, not inferred.
- Anchors: `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/KoreWiring.kt`,
  `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ListenCheck.kt`
