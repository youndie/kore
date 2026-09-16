---
id: B-58
title: "Should the oracle drive a distribution as well as an image?"
status: done
priority: P2
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-58 — Should the oracle drive a distribution as well as an image?

Asked from a consumer as [#85](https://github.com/youndie/kore/issues/85), as a question with three
options and no preference: add `--distribution`, declare it out of scope, or leave it to the consumer.

**Answered by adding it**, and the argument that decided it is kore's own central finding:
`EmbeddedServer.stop` runs its steps in the opposite order on the JVM and on Kotlin/Native, from
identical source. A consumer's JVM artefact is therefore exactly where a shutdown defect can hide
that the native run cannot see — and if that artefact is a distribution rather than an image, the
instrument that would catch it could not be pointed at it.

## The objection that would have decided the other way was checked and does not hold

A start script is a shell wrapper, and this repository carries a paragraph on why a shell wrapper is
how a signal goes missing: `/bin/sh -c` becomes PID 1 and does not forward `SIGTERM`. If that applied
here, driving a distribution would be testing a configuration that differs from what ships in the one
respect kore calls load-bearing.

It does not. Gradle's generated start script ends in `exec "$JAVACMD" "$@"` — read out of a generated
one in this portfolio, not recalled — so the shell replaces itself and the pid the harness holds is
the JVM's.

## What it cost, and why that is the whole answer

Six methods. Everything else in the run is already transport agnostic: the drivers speak HTTP to a
port, the poller speaks HTTP to a port, and every assertion reads `Observations`. So `Subject` is an
interface with `start`, `pid1`, `port`, `sigterm`, `awaitExit`, `remove` — and `LocalProcess` is sixty
lines rather than a second harness. `--command` is an executable and its arguments, so it covers a
start script and a `java -jar` alike.

**Rejected: a test-only image in the consumer's repository.** The reporter's argument is right and
general — a template's clones inherit whatever is in it, including an image that exists only to be
measured.

## Demonstrated on kore's own JVM artefact, with no container

```
PID 1: java -jar …/service-all.jar --kore=true (pid 281712)
  PASS  A1 in-flight requests finished — 8 spanned the signal, all completed
  PASS  A4 readiness fell before the first refusal — by 2968ms
  PASS  A6 exited itself inside the grace period — exit 143 after 15084ms of 30000ms
result: 8 passed, 0 failed, 0 inconclusive, 1 not applicable
```

Exit `143` is the JVM's clean `SIGTERM`, which is what `A6` is written to accept beside Kotlin/Native's
`0`.

## Two things this found on the way

**`--command` is comma-separated**, and the first draft used spaces and met the trap within the hour:
Gradle's `--args` splits on spaces, so `--command=java -jar app.jar` reaches the oracle as three
arguments and the subject becomes `java` with nothing to run. `--subject-args` already carried the
same comment; a second flag repeated the mistake rather than inheriting the lesson.

**A dead subject is now reported as dead.** It failed as `the container never answered /health` after
thirty seconds of waiting — a message that reads as a subject which will not start and says nothing
about why. That message has now hidden two different failures: a required configuration key nobody
supplied ([B-54](B-54-oracle-drives-any-path.md)) and an executable path that did not resolve. The
wait asks `isAlive()` and stops.

- AC: the oracle drives a process as well as a container, and the assertions are unchanged by which.
  **Met**, demonstrated on kore's own JVM artefact.
- Anchors: `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Subject.kt`,
  `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Oracle.kt`
