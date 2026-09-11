---
id: B-05
title: "The sample service: one source, two binaries"
status: done
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

## Iteration 1 — 2026-09-11

**Done.** Two binaries from one source set, two images, both taking `SIGTERM` at PID 1.

| Checked | How |
|---|---|
| both binaries start and serve `/work?ms=` | run in their containers; `/health` → `ok`, `/work?ms=100` → `worked for 100ms` on each |
| the signal reaches PID 1 | `/proc/<pid>/cmdline` of the container's init read **from the host**: `/app/service` and `java -jar /app/service.jar`. `docker exec` cannot answer this on a distroless image — there is no `cat` — and a check that cannot run on one of the two variants is not a check |
| both stop on `SIGTERM` | `docker kill -s TERM`: 472 ms (jvm) and 535 ms (native), idle |
| the native link cost, inherited from [B-07](B-07-native-link-cost.md) | `linkReleaseExecutableLinuxX64` **35.3 s**, `linkDebugExecutableLinuxX64` **1.4 s** — twenty-five to one |

**The finding that corrected a document:** the two platforms return **different exit codes for the
same clean shutdown** — `0` on Kotlin/Native (`main` returns) and `143` on the JVM (`128 + SIGTERM`,
after the shutdown hooks run). Oracle assertion A6 demanded `0` and would have failed every correct
JVM run. It now asserts that the process ended itself inside the budget and was not `SIGKILL`ed, and
records the code instead of judging it. Found before anything was built on it, which is the whole
reason the fixture comes before the harness.

**The measurement that was wrong the first time.** The release link came out at 0.76 s, which is the
Gradle build cache handing back an entry `clean` had not removed. `--no-build-cache --rerun-tasks` is
what makes the task run; the honest number is 35.3 s. Written into `CLAUDE.md` as a rule, because
`org.gradle.caching=true` is on in this repository and the same trap is set for every future
measurement.

**Two things left deliberately undone, both moved rather than dropped:**

1. **The pooled dependency.** B-05's acceptance did not need one, and choosing a store that exists on
   both targets decides what the readiness check can do — so it belongs to
   [B-18](B-18-pooled-store-check.md), which has to choose one anyway.
2. **The configuration schema with a near-miss name.** It needs [B-21](B-21-config-schema.md), which
   does not exist yet. The sample's port is a constant until it does, and says so in the source.

**Also here:** the published target set moved from "every subproject" to "the `kore-*` modules". The
sample declares `jvm()` and `linuxX64` itself — it was producing `linuxArm64` and `macosArm64`
metadata jars for an experiment that runs on neither.

**Not done and not needed here:** the load driver and the assertions of research-oracle §2.3, which
are [B-06](B-06-oracle-harness.md). This item built the thing B-06 drives.
