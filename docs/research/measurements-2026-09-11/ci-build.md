# CI build time, 2026-09-11 — and what it does not yet measure

Taken for [B-07](../../backlog/B-07-native-link-cost.md) on `github.com/youndie/kore`, runner
`ubuntu-latest`, from the workflow's own job timings and the Gradle line in each job's log. Two runs
on the `feat/b-07-build-in-ci` branch, in order.

| Run | Caches available | Job | `BUILD SUCCESSFUL in` |
|---|---|---|---|
| [34648544559](https://github.com/youndie/kore/actions/runs/34648544559) | none | **1m 48s** | 1m 28s |
| [34648741010](https://github.com/youndie/kore/actions/runs/34648741010) | `~/.konan` only | **1m 39s** | 1m 15s |

`~/.konan` is 565 MB (592 437 367 B) and restored in the second run at 28–97 MB/s.

## What the difference is, and is not

The second run is 9 s faster as a job and 13 s faster inside Gradle. That is **the `~/.konan` cache
alone**, and the number is small because this project is small: the cache removes a fixed cost — the
Kotlin/Native distribution and JetBrains' toolchain downloads (`aarch64-unknown-linux-gnu-gcc`,
`llvm-21-x86_64-linux-essentials`, `libffi`, `qemu-aarch64-static`, `lldb`) — while the compilation
it is competing against is currently seconds. It does not scale with the code; the compile does.

**The Gradle cache was never exercised, in either run**, and the log says why:

```
cache-read-only: true
Gradle User Home cache not found. Will initialize empty.
```

`gradle/actions/setup-gradle@v4` writes its cache **only from the default branch**. Both runs here
are on a pull-request branch, so both were read-only against a cache that has never been written.
It will be written the first time this workflow runs on `main` — which is when this pull request
merges — and only then can a genuinely warm run be measured.

So the honest reading of the table is: *cold everything* against *konan cached, Gradle not*. Neither
row is the steady state.

## What is deliberately absent

**No executable is linked anywhere in this build.** `./gradlew build` produces klibs and jars; the
expensive Kotlin/Native operation — `linkDebugExecutableLinuxX64` — has no subject yet, because
nothing in this repository has a `main`. B-07 was written about the cost of a *link*, and the link
is still unmeasured. The first thing to produce one is the sample service, so the re-measurement is
carried there: [B-05](../../backlog/B-05-sample-service.md).

## The decision this supports

At ~1m40s, and with the link still to come, the build runs **on every pull request and on `main`**.
The worry B-07 was written around — that a native build might be too slow for a per-pull-request
gate and would have to fall back to per-milestone — is refuted at this size and must be re-asked
once something links.

A fallback of "JVM per pull request, native per milestone" was considered and is recorded as
rejected: a gate that covers one platform while the library claims two is a gate with a hole in it,
and this library's entire argument is a difference between the two platforms.

---

# The native link, measured — added the same day by [B-05](../../backlog/B-05-sample-service.md)

B-07 could not measure a link because nothing had a `main`. The sample service does, so here it is.

Taken on the Linux build box (20 cores), `~/.konan` warm, **`--no-build-cache --rerun-tasks`** after
`clean`:

| Task | Wall |
|---|---|
| `linkReleaseExecutableLinuxX64` | **35.3 s** |
| `linkDebugExecutableLinuxX64` | **1.4 s** |

**The first attempt at this measured 0.76 s and was wrong.** `org.gradle.caching=true` is on in this
repository, so `clean` removed the output while the build cache still held the entry and handed it
back. The number describes the cache, not the linker. `--no-build-cache --rerun-tasks` is what makes
the task actually run — the same shape of mistake as measuring a warm process and calling it a cold
start.

**Release is twenty-five times debug**, which is the whole of the answer: the optimising LLVM pass is
the cost, and a debug link is nearly free. That is worth knowing before anyone proposes dropping the
release link from the gate to save time — and worth re-asking when there is more than one small
module to optimise.

## The two images, for the same reason

Built from `samples/service/Dockerfile`, both entry points in exec form:

| Image | Size |
|---|---|
| `kore-sample:native` (`distroless/cc-debian13` + `libcrypt.so.1`) | **47.7 MB** |
| `kore-sample:jvm` (`eclipse-temurin:25-jre`) | **493 MB** |

`libcrypt.so.1` is copied in explicitly: `ldd` on the binary lists it and `distroless/cc` does not
carry it. Read rather than assumed, and it is the kind of gap that shows up as a container which
will not start.

## Stopping, with nothing in flight

A baseline for [B-34](../../backlog/B-34-the-three-numbers.md), not a result: `docker kill -s TERM`
against an idle container, from the signal to the container exiting.

| Image | Time | Exit code |
|---|---|---|
| jvm | 472 ms | **143** |
| native | 535 ms | **0** |

The exit codes are the finding. A clean shutdown returns `143` (`128 + SIGTERM`) on the JVM, because
the JVM runs its shutdown hooks and then dies of the signal, and `0` on Kotlin/Native, because `main`
returns. Oracle assertion A6 said "exited with code 0" and would have failed every correct JVM run;
it now asserts that the process ended itself inside the budget and was not `SIGKILL`ed, and records
the code rather than judging it.

These numbers say nothing about a shutdown *under load*, which is the one the oracle takes.

---

# The oracle's first runs — [B-06](../../backlog/B-06-oracle-harness.md)

Closed loop, 8 keep-alive connections on `/work?ms=3000`, `SIGTERM` to PID 1, every observation from
the client's own record. Run on the Linux build box.

| Image | `shutdownGracePeriod` | A1 in-flight finished | Exit | After the signal |
|---|---|---|---|---|
| `kore-sample:jvm` | default (**1000 ms**) | **FAIL** — 8 of 8 cut off | 143 | 1435 ms |
| `kore-sample:native` | default (**1000 ms**) | **FAIL** — 8 of 8 cut off | 0 | 1599 ms |
| `kore-probe:jvm` | 20 000 ms | PASS — 8 of 8 completed | 143 | 20 452 ms |
| `kore-probe:native` | 20 000 ms | PASS — 8 of 8 completed | 0 | 20 568 ms |

The `kore-probe:*` images are the same source with `shutdownGracePeriod = 20_000` and
`shutdownTimeout = 25_000`; the change was **not** kept, because the sample is the control and the
control includes Ktor's defaults.

Three things come out of this table and none of them was the question it was built to ask.

1. **Ktor's default grace period is one second**, so an unconfigured service drops every request
   slower than that, on both platforms, for a reason unrelated to ordering.
2. **A run at the default cannot see the ordering question at all.** The first two rows look exactly
   like §1.1's prediction and are not it. Written up as confirmation, they would have been a
   confident wrong conclusion.
3. **The drain serves rather than refuses.** In the 20-second runs the server handled **48 further
   requests** on already-open connections after the signal, with **no `503` and no
   `Connection: close`** — `exchanges: 64, spanning the signal: 8, after it: 48`. And it spent the
   entire grace period doing it: ~20.5 s to exit for ~3 s of outstanding work.

The harness's own first run was also wrong, and that is recorded rather than quietly fixed: A4
reported **PASS** against a subject with no `/health/ready` at all, because "no readiness endpoint"
was written as *every* poll returning 404 and the polls after shutdown return `null`. An assertion
that passes where it has no subject is the exact failure this repository is written against, so the
harness now reports `NOT_APPLICABLE` as a first-class verdict and an `INCONCLUSIVE` run exits
non-zero.
