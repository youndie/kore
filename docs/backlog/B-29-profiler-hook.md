---
id: B-29
title: "Decide the shape of the profiler hook, or drop it"
status: question
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

- AC: a decision recorded here — a shape, or a refusal with its reason. If a shape, a feature document
  follows; if a refusal, the brief's bullet is marked as dropped in research §3 with the reasoning.
- Anchors: `docs/research/research-architecture.md`
