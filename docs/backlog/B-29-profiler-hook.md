---
id: B-29
title: "Decide the shape of the profiler hook, or drop it"
status: done
priority: P3
size: S
stage: m5-wiring
---

# B-29 — Decide the shape of the profiler hook, or drop it

Open question 1 of [research-architecture](../research/research-architecture.md) §3, and the least
defined item in the brief.

- **The hypothesis.** kore owns *enabling* a profiling endpoint or an on-demand dump and nothing else,
  because the profilers differ per platform — JFR and async-profiler on the JVM, nothing equivalent on
  Kotlin/Native. If that holds, the honest shape is a small extension point plus a JVM adapter, and on
  native the hook exists and does nothing — which `--print-config` must **state**, not leave to be
  discovered.
- **Rejected in advance:** a hook that silently no-ops on native. Every other capability gap in this
  library is declared (see [B-22](B-22-environment-enumeration.md)); this one does not get an exception.
- **Dropping it is an acceptable outcome**, and saying so now is the point of writing the item as a
  question. A feature document describing a shape nobody has settled would be describing an intention,
  which is what this repository's first rule forbids.

## Decided 2026-09-12: dropped — a deviation from the brief

The item said dropping it is an acceptable outcome. It is the outcome, for four reasons, and the
first two are about positions kore has already taken rather than about profilers.

**1. As a route it contradicts two decisions.** The route list is closed — *"five routes and there
will not quietly be a sixth"* — and none is authenticated, because a deploy check runs before anything
has a token. A profiling endpoint is the one route that **must** be authenticated: it dumps stacks and
memory, and it is expensive to call. kore would have to ship either an unauthenticated dump endpoint
or an auth story it does not have.

**2. As a plain hook it adds nothing kore is positioned to add.** Everything else kore wires removes a
failure mode — a flush never called, a close that discards, a stage that runs before the drain.
Starting a profiler is a deployment flag, and the one shutdown-shaped risk — losing the buffer at exit
— the JVM already solves declaratively. Verified rather than recalled: `java
-XX:StartFlightRecording:help` on Java 25 prints `dumponexit` as an option and
`-XX:StartFlightRecording:dumponexit=true` as its own example. kore would be duplicating a flag.

**3. On the primary target there is nothing to hook.** Kotlin/Native has no JFR and no equivalent, so
the honest implementation is "exists and does nothing" on the platform this library is aimed at. kore
exists because a thing that works on the JVM and quietly does nothing on native is the shape of bug it
was written to prevent; shipping one of its own would teach the opposite of everything else here.

**4. It is the only bullet in the brief that names a capability rather than a failure.** The other
four each name something this portfolio has actually lost: a flush, a batch, a probe that answers for
the wrong question, a deployment that cannot say what it is running. That difference is why this one
was the least defined from the first day.

**What is not dropped is the need.** A service that wants a profiler gets one from its deployment,
with a flag. kore neither helps nor gets in the way — which is now written down instead of left open,
and that is the whole difference between a decision and an omission.

- AC: a decision recorded here — a shape, or a refusal with its reason. If a shape, a feature document
  follows; if a refusal, the brief's bullet is marked as dropped in research §3 with the reasoning.
- Anchors: `docs/research/research-architecture.md`
