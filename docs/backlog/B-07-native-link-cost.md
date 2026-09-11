---
id: B-07
title: "Measure what a native link costs CI, and decide the gate's cadence"
status: wip
priority: P0
size: S
stage: m1-oracle
blocked_by: [B-01]
---

# B-07 — Measure what a native link costs CI, and decide the gate's cadence

Risk 5 of [research-architecture](../research/research-architecture.md) §3: everything kore is about
is only observable on a native binary under a real signal, and a native link is not free.

> **Raised to P0 and taken out of numeric order on 2026-09-11, with the reason recorded here rather
> than left to look like a whim.** The loop was given permission to merge its own green pull
> requests. "Green" currently means `make check` — the documentation gate — and **nothing in CI
> compiles a line of Kotlin.** Two pull requests have already merged on that signal. They were safe
> because the build was run by hand on the Linux box each time, which is not a property of the
> repository; it is a property of who happened to be driving. A gate whose job is named `check` and
> checks none of the code is exactly the shape this repository refuses everywhere else.
>
> So this item's content is now two things, not one: **put a build in CI at all**, and then answer
> the question it was written for — what it costs and how often it should run.

- **The decision and its reason.** Measure before deciding whether the oracle runs per pull request
  or per milestone. A suite whose cadence is chosen by guess is one that is either too slow to keep
  or too rare to catch anything, and the answer gets expensive to change once the suite is large.
- **Rejected:** assuming it is fine because the portfolio's other native projects manage. Their link
  is a different size and their cache is differently warm; the number here is about this build.
- Does **not** cover: buying runners. If the answer is "too slow for a pull request", the fallback is
  the JVM variant per pull request and both per milestone — which must then be stated, because a
  gate that covers one platform while the library claims two is a gate with a hole in it.

- AC: a measured cold and warm link time for `linuxX64`, written down with the machine it was taken
  on; a decision recorded in this item and reflected in the workflow.
- Anchors: `.github/workflows/`
