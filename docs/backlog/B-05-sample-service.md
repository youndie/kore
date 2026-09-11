---
id: B-05
title: "The sample service: one source, two binaries"
status: wip
priority: P0
size: M
stage: m1-oracle
epic: feature-ordered-shutdown
blocked_by: [B-01]
---

# B-05 — The sample service: one source, two binaries

The fixture described in [sample-service](../services/sample-service.md): a slow route, one pooled
dependency, one stand-in consumer, one awkward configuration schema — built for `jvm` and
`linuxX64` from one source set.

- **The decision and its reason.** One source, two entry points. A JVM-shaped `main` beside a
  separately written native one would make assertion A7 — "the two platforms agree" — an assertion
  that two different programs behave alike, which is not the claim.
- **Rejected:** a sample per platform, and a sample with a domain. Every line in this one is there
  because an assertion reads it.
- Open inside this item: **which pooled store**. It has to exist on both targets, which is a smaller
  set than it looks, and the choice decides what the readiness check of B-18 can do.

- AC: both binaries start, serve `/work?ms=`, and are containerised with the signal reaching PID 1.
- **AC added from [B-07](B-07-native-link-cost.md):** measure `linkDebugExecutableLinuxX64` in CI,
  cold and warm, and write it beside the build numbers in
  `docs/research/measurements-2026-09-11/ci-build.md`. B-07 was written about the cost of a *link*
  and could not measure one — `./gradlew build` produces klibs, and nothing in the repository had a
  `main`. This item is the first thing that will. If the number turns the per-pull-request gate into
  a bad trade, that decision is re-opened here rather than assumed settled.
- Anchors: `samples/service/`, `samples/service/Dockerfile`
