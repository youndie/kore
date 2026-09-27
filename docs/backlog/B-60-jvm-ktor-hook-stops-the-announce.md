---
id: B-60
title: "On the JVM, Ktor's own shutdown hook stops the engine at SIGTERM, before kore's announce"
status: done
priority: P1
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-60 — On the JVM, Ktor's own shutdown hook stops the engine at SIGTERM, before kore's announce

Reported from a service built from keel as [#90](https://github.com/youndie/kore/issues/90): on the
JVM, `SIGTERM` closed the listener at once, readiness was a refused connection instead of a `503`, and
the announce kore spent five seconds on was heard by nobody. The `linuxX64` build of the same wiring
answered `503` throughout.

## Why

`EmbeddedServerJvm.start` calls `addShutdownHook { stop() }` on its first line. On Native that is the
one global slot and kore's handler, installed later, takes it — research §1.3, consequence 3. On the
JVM it is one more `Runtime` hook, the JVM starts every hook concurrently, and Ktor's calls `stop()`
while kore's sequence is announcing. §1.3's table carried both facts — the JVM row and the switch —
and consequence 3 reasoned only about Native. Risk 2 predicted the defect for "a future Ktor that
changes the slot to a list"; on the JVM it had been a list all along.

## Reproduced here before anything was changed

On kore's own JVM jar, with the oracle's new A8 (below), `--pre-drain=5000`:

```
PASS  A4 readiness fell before the first refusal — by 2901ms
FAIL  A5 the pre-drain wait was honoured — 2901ms < 5000ms
FAIL  A8 new connections were answered through the announce — 48 of 48 new connections were refused
      inside the 5000ms announce, the first 13ms after the signal — the listener closed before the drain
```

## Why the oracle did not see it

**Its readiness poller rides one keep-alive connection, and the engine keeps serving open connections
through its grace period after the listener is gone.** So kore's `503` arrived on that connection
whether the listener existed or not, and A4 passed — in B-39 against the JVM image, and in B-58
against this very jar. A kubelet opens a connection per probe, so the defect was invisible to exactly
the instrument built to find it, and fully visible to the cluster. A5 goes red above only because the
drivers' three-second requests end inside a five-second announce; B-58's run gave no pre-drain and
A5 was not asked.

**A8** is a second poller that opens a new connection each time and keeps going after a refusal, and
an assertion that every probe inside the pre-drain wait (less 200 ms) answered, and at least one
answered `503`. Six unit cases, including "keep-alive saw 503, new connections were refused".

## The fix

- **`startForKore()`** in `kore-ktor`: sets `io.ktor.server.engine.ShutdownHook=false`, then
  `start(wait = false)`. Ktor copies the property into a top-level `val` when `ShutdownHookJvmKt` is
  first loaded, which is the first line of `start()` — so the issue's first candidate,
  `installKoreProbes`, runs too late, and the switch has to live in the call that starts.
- **`EngineDrain` refuses to be built beside a hook that is on**, naming the fix. It reads the value
  Ktor actually fixed (the `internal` getter is public in bytecode), not the property, which is only
  what Ktor *would* read. A Ktor that renames it answers "cannot tell", never "fine".
- Native: both are no-ops.

**Rejected: a warning instead of the refusal.** A hook that is on makes the drain stage's position a
fiction; a startup refusal is found by whoever bumps kore, a warning by nobody. The price: a consumer's
JVM tests that build `EngineDrain` in a process where `testApplication` already started a server need
`-Dio.ktor.server.engine.ShutdownHook=false` on the test task, as `kore-ktor`'s own do.

## Verified

- `KtorShutdownHookJvmTest`: a child JVM gets a real `SIGTERM`, `/health/ready` is probed on new
  connections through a two-second announce. `startForKore()` — every probe `503`. Plain `start()` —
  refused (the control; green means the probe can see it). `start()` + `EngineDrain` — exits 3 with
  the message.
- **Mutations**, each killed by the named test: `switchOffKtorShutdownHook()` removed together with
  the guard — `under startForKore…` fails with `[200, null, null, …]`; the guard alone removed —
  `EngineDrain refuses…` fails. The second mutation first made that test **hang** for ten minutes
  (reading the subject's pipe to its end), then fail on `Stream closed`; it now waits on a file.
- The oracle, 2026-09-27, on the Linux box, `--work=3000 --connections=8`, one port per run:

| subject | pre-drain | A8 — new connections inside the announce | A5 |
|---|---|---|---|
| JVM jar, **before** | 5000 | **48 of 48 refused**, the first at 13 ms | FAIL 2901 ms |
| JVM jar, after | 2000 | 18 of 18 answered, `503` from 119 ms | PASS |
| native, after | 2000 | 17 of 17 answered, `503` from 88 ms | PASS |
| JVM jar, after (two runs) | 5000 | 45 of 47 and 46 of 47 — refusals at ~3.9 s, none earlier | FAIL 2891 / 2989 ms |
| native, after (two runs) | 5000 | 47 of 47 answered | FAIL 2905 / 2923 ms |

**The hook is off:** with it on, every probe after the first 13 ms is refused. **What the five-second
rows show is something else, and it is older than #90.** A5 fails on native as well, so the
platform does not matter. The sample gates its `503` refusal on `readiness.isShuttingDown`, which
the **announce** sets. So kore refuses from the first millisecond of the announce, not from the
drain. A5 passed before only because the drivers' three-second requests outlasted the two-second
default pre-drain. The same pattern as #90: the instrument passed by coincidence. Once the requests
end inside the announce, every driver gets `503` + `Connection: close`, reconnects, and is refused
again. That is about 4 000 exchanges in two seconds on the JVM and 7 000 on native. The JVM's one
or two late A8 refusals happen inside that storm; native absorbs it. The timing of the refusal is
its own item, not this one: [B-61](B-61-refusal-starts-at-the-announce.md).

**Three native starts died at bind with `EADDRINUSE`** — CIO's own bind (`tcpBind` in `httpServer`'s
accept job). None was on a fresh port. One was on the port the previous JVM run had just used. The other
two were on a port that, it turned out, had carried a full native run moments earlier, in an attempt
whose output `set -e` swallowed. The likely mechanism was measured on keel rather than here: CIO
defaults to `reuseAddress = false`, and on native Ktor applies that literally, while the JVM's NIO sets
`SO_REUSEADDR` on its own. So a `TIME_WAIT` left on the service port refuses the native bind and not
the JVM one. Here it did **not** reproduce: two back-to-back native runs on one port showed no
`TIME_WAIT` before the second, which then started. That fits research §1.4 — CIO does not hang up on
`Connection: close`, so the oracle's client closes first and the `TIME_WAIT` sits on the client's
side. The three deaths are consistent with the mechanism, and they are not proven by it.

- AC: on the JVM, after `SIGTERM`, readiness answers `503` for the announce and the engine stops only
  in `DRAIN`, the same as on `linuxX64`. **Met** for the engine: no refusal at the signal on either
  platform. Under the oracle's reconnect storm the JVM still refuses about one new connection in 47
  late in a long announce. That storm exists only because the refusal starts at the announce, so it
  is left open with that item rather than closed here. **Closed by
  [B-61](B-61-refusal-starts-at-the-announce.md):** with the refusal moved to the drain, the JVM's two
  five-second runs answered 46 of 46 new connections each, where this item's had 45 and 46 of 47.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/StartForKore.kt`,
  `kore-ktor/src/jvmMain/kotlin/io/github/youndie/kore/ktor/StartForKore.jvm.kt`,
  `kore-ktor/src/jvmTest/kotlin/io/github/youndie/kore/ktor/KtorShutdownHookJvmTest.kt`,
  `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Assertions.kt`
