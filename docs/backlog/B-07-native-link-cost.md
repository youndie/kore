---
id: B-07
title: "Measure what a native link costs CI, and decide the gate's cadence"
status: done
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

## Iteration 1 — 2026-09-11

**Done, with one half of the acceptance criterion moved rather than ticked.**

The workflow now has a `build` job: `./gradlew build` for all four targets on `ubuntu-latest`, with
`~/.konan` cached separately from Gradle — keyed on the Kotlin version, because that is what decides
which distribution is fetched and a lockfile does not name it. Its last step prints every
test-result file with its counts and **fails when there are none**, because a suite that ran zero
tests exits zero and on a multiplatform build the suites that silently do not run are the
interesting ones.

Numbers, both taken from the runs this branch produced —
[the record](../research/measurements-2026-09-11/ci-build.md):

| Caches | Job | Gradle |
|---|---|---|
| none | 1m 48s | 1m 28s |
| `~/.konan` (565 MB) | 1m 39s | 1m 15s |

**Decision: the build runs on every pull request and on `main`.** The worry this item was written
around — that a native build might have to fall back to per-milestone — is refuted at this size.
The rejected alternative is recorded too: "JVM per pull request, native per milestone" is a gate
covering one platform while the library claims two, and this library's whole argument is a
difference between the two platforms.

**Two things the measurement found that were not the question:**

1. **The Gradle cache has never been written.** `gradle/actions/setup-gradle@v4` is `cache-read-only`
   on any branch that is not the default one, so both runs read a cache that does not exist
   (`Gradle User Home cache not found. Will initialize empty.`). It gets written the first time the
   workflow runs on `main` — when this merges. So neither row above is the steady state, and the
   table says so rather than presenting the warm row as one.
2. **Nothing links.** `./gradlew build` produces klibs and jars; `linkDebugExecutableLinuxX64` has no
   subject, because no module has a `main`. This item was written about the cost of a *link*, so that
   half of its acceptance criterion is **moved to [B-05](B-05-sample-service.md)**, which is the
   first thing that will produce an executable — not ticked here, and not quietly dropped.

**Also in this change:** `push` narrowed to `main`. Every item here opens a pull request, so an
unrestricted `push` ran the whole workflow twice per commit, which stopped being a rounding error
the moment the workflow started compiling. Plus a `concurrency` group that supersedes a pull
request's own earlier runs and never cancels one on `main`, where the run is the record of what that
commit did.
