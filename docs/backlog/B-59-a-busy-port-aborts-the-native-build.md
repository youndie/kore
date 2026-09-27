---
id: B-59
title: "A busy port aborts a Kotlin/Native service instead of refusing to start"
status: done
priority: P2
size: S
stage: m4-config
epic: feature-typed-config
blocked_by: []
---

# B-59 — A busy port aborts a Kotlin/Native service instead of refusing to start

Found by a service built from keel and filed there as
[keel#49](https://github.com/youndie/keel/issues/49); it belongs here because keel's rule sends
start-up refusals to kore, and the configuration check is kore's.

Started on a port another process holds, the native build printed `Uncaught Kotlin exception`, a
`JobCancellationException` caused by `PosixException.AddressAlreadyInUseException: EADDRINUSE (98)`,
and died with `SIGABRT` — exit 134, 53–59 lines of stack, and a core dump where the host allows one.
The JVM build exited 1 with 18 lines. Neither named the variable an operator has to change, while
every other refusal at start-up is kore's one sentence and exit 1.

**The mechanism is Ktor's.** CIO binds inside a coroutine it launches itself, after
`start(wait = false)` has returned (`ktor-server-cio-3.6.0-sources.jar!/commonMain/io/ktor/server/cio/backend/HttpServer.kt`,
the `accept-` job). The bind failure reaches that coroutine's root with no handler, and on
Kotlin/Native an unhandled coroutine exception ends the process. Nothing of the service's code stands
between the two. Recorded, not filed, as
[research-upstream-proposals](../research/research-upstream-proposals.md) §1.3.

- **Decision: bind once before the engine, and refuse as a configuration problem.**
  `Configuration.requireListenable(port)` binds and closes the port and, on failure, throws the
  `ConfigurationException` a consumer already catches for the rest of the configuration — so a busy
  port is `SVC_PORT: 8080 cannot be listened on: <reason>` and the same exit as a missing variable,
  with no new code at the call site beyond the one call.
- **Decision: the engine's bind, in `kore-ktor`.** The check has to fail where CIO would and with
  the same option (`reuseAddress`, CIO's default `false`). On the JVM that is `ktor-network` itself,
  added to `kore-ktor`'s JVM classpath; a CIO consumer has it already, and it is not an engine. **On
  Kotlin/Native it is posix calls that copy `ktor-network`'s `tcpBind`** — this bullet first said
  `ktor-network` on both, and the section below is why it stopped.
- **Stated as a narrowing, not a fix.** Something can take the port between this bind and the
  engine's. The common case — the port is already held — becomes a sentence; the race stays Ktor's,
  and a process that loses it still aborts.
- Not covered: the sample. It binds a port too, but it is the oracle's subject, and its control arm
  is deliberately the naïve service.

- AC: a busy port throws a `ConfigurationException` whose one problem names the variable and the
  port, on jvm and linuxX64. **Met** — `ListenCheckTest`, 3 tests on each target, read from the
  result XML.
- AC: a test that fails if the refusal is removed. **Met** — replacing the refusal with a return
  fails `a port another socket holds refuses the start and names its variable` and only that test,
  on both targets.
- AC: the check does not keep the port. **Met** — `the check holds no descriptor once it returns`
  (linuxX64) and `the engine can bind the port the moment the check returns` (both targets); see
  below for why it is two tests and which one can fail.
- AC: a consumer's binary refuses in one sentence on both builds. **Settled in keel**, which adopts
  this after a release: the service that found it measured exit 1 and one line on native and JVM
  with the same bind, and keel#49 is closed by that measurement against keel's own binary.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ListenCheck.kt`,
  `kore-ktor/src/nativeMain/kotlin/io/github/youndie/kore/ktor/ListenCheck.native.kt`,
  `kore-ktor/src/jvmMain/kotlin/io/github/youndie/kore/ktor/ListenCheck.jvm.kt`,
  `kore-ktor/src/commonTest/kotlin/io/github/youndie/kore/ktor/ListenCheckTest.kt`,
  `kore-ktor/src/linuxTest/kotlin/io/github/youndie/kore/ktor/ListenCheckDescriptorTest.kt`

## The first version kept the port it was checking, on native, sometimes

Merged in #91 through `ktor-network` on both targets, green on every job. The next pull request's CI
failed `the check leaves the port free for the engine` on linuxX64: the check had passed, and a bind
of the same port straight after it was refused.

`ktor-network`'s native server socket does not close its descriptor in `close()`. It shuts it down
and **queues** it for the selector thread (`ktor-network-3.6.0-sources.jar!/posixMain/io/ktor/network/sockets/TCPServerSocketNative.kt`,
`close` → `notifyClosed`), so for a moment after `close()` returns the port is still bound — and the
engine binds it next. A check that sometimes causes the failure it was written to prevent. The JVM's
`close()` is the channel's and synchronous, so only the native half changed: it now makes the same
calls `tcpBind` makes, through posix, and `close(2)` has happened by the time it returns.

**The first guard written for it could not fail.** Two hundred engine binds straight after the check
passed against the old implementation twice on the Linux box: the selector thread is usually faster
than the next line. What fails is counting `/proc/self/fd` immediately after the call — the old
implementation fails that on both of two uncached runs, the new one passes. The bind loop is kept at
fifty as the consequence a consumer cares about, and it is not the test that proves anything.

The keel measurement above was taken with the first version and passed, because keel opens its
database between the check and the engine's bind; it would not have caught this either.
