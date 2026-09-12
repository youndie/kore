# The three numbers — [B-34](../../backlog/B-34-the-three-numbers.md), 2026-09-12

The measurement plan of [research-oracle](../research-oracle.md) §4, taken as comparisons: the same
binary with kore and without it, alternating, in one run.

## What was measured, and on what

| | |
|---|---|
| Subject | `samples/service`, one binary, two arms selected by `--kore=true` |
| Images | `kore-sample:jvm` (`eclipse-temurin:25-jre`), `kore-sample:native` (`gcr.io/distroless/cc-debian13`, `linuxX64` release) |
| kore | branch `feat/b-34-three-numbers`, commit `2d589fbb4d61` |
| Host | the project's Linux build host — 20 cores, 15 GB, kernel 6.6.87.2, Docker 29.1.3 |
| Load | closed loop, 8 keep-alive connections on `/work?ms=3000` |
| Repetitions | 5 per cell, **plus one warm-up per cell that was discarded** and is printed as discarded |
| Grace | 20 s given to the harness; the control's own Ktor grace is its default 1000 ms |
| Raw output | [`raw-three-numbers.txt`](raw-three-numbers.txt) |

`./gradlew :samples:oracle:measure --args="--repeats=5 --work=3000 --connections=8"`

**The binary cannot name its own commit here.** `/version` reports `unknown`, because the build host
holds no VCS metadata, so the commit above is this working tree's and not the container's word for
it. That is a property of where the images are built, not of the identity mechanism — which is
[feature-build-identity](../../features/feature-build-identity.md)'s documented degradation.

## The numbers

Median (min–max), n=5.

| image | number | control | kore | difference |
|---|---|---|---|---|
| jvm | time to first `/health` | 1177 ms (1129–1238) | 1189 ms (1133–1260) | **+12 ms (+1%)** |
| jvm | RSS at ready | 109 920 kB (109 760–111 200) | 115 680 kB (111 008–116 112) | **+5 760 kB (+5%)** |
| jvm | stop under load | 1475 ms (1440–1513) | 3503 ms (3488–3522) | **+2028 ms (+137%)** |
| native | time to first `/health` | 719 ms (706–744) | 724 ms (688–745) | **+5 ms (+1%)** |
| native | RSS at ready | 26 080 kB (25 280–26 400) | 28 000 kB (26 400–28 160) | **+1 920 kB (+7%)** |
| native | stop under load | 1483 ms (1447–1577) | 3483 ms (3444–3518) | **+2000 ms (+135%)** |

kore's own `/health/startup` — which the control has no equivalent of, so it is reported alone and
not as a comparison — first answered `200` at **1194 ms (1137–1267)** on the JVM and **726 ms
(690–748)** on native.

### The third number is not a cost, and reporting it without the next table would be a lie

| image | arm | requests spanning the signal | finished | dropped |
|---|---|---|---|---|
| jvm | control | 40 | 0 | **40** |
| jvm | kore | 40 | **40** | 0 |
| native | control | 40 | 0 | **40** |
| native | kore | 40 | **40** | 0 |

**The control stops two seconds sooner because it drops every request it was serving.** Forty of
forty, on both platforms, in every run. kore takes 2 s longer and finishes all forty. The +137 % is
the price of the work not being thrown away, and a reader shown only the stop column would read the
wrong sign.

This is the negative control of [2026-09-11](../measurements-2026-09-11/negative-control.md) reproduced
from a different harness: an unconfigured Ktor service cuts off in-flight work at its 1000 ms default
grace, on both platforms, with no ordering involved.

## What the other two say

**Startup costs nothing measurable.** +12 ms on a 1.2 s JVM start and +5 ms on a 0.7 s native start
are inside the spread of either arm. kore is not free — it installs two plugins and a route — but at
this resolution the cost does not separate from the noise.

**RSS costs 2–6 MB.** +5.8 MB on the JVM, +1.9 MB on native; as a fraction, 5 % and 7 %. Both halves
are reported because neither decides anything alone: 7 % sounds larger than 1.9 MB reads, and 1.9 MB
sounds smaller than 7 % of a 26 MB resident set.

## Three things the harness got wrong first, and how they were caught

Recorded because each produced a plausible number that was not a measurement.

1. **The images were stale, and nothing said so.** `samples/service` gained a `version` in B-26,
   which renamed the fat jar to `service-0.1.0-sample-all.jar` — while the Dockerfile went on copying
   `service-all.jar`, a leftover from before that change still sitting in `build/libs`. The JVM image
   had been built from hour-old code ever since. A *missing* file fails `COPY` loudly; a *stale* one
   fails nothing. Fixed by pinning `archiveFileName`, and the harness now refuses an image whose kore
   arm does not serve `/version`.
2. **The harness exhausted the ports it needed.** Polling every 10 ms with a new connection each time
   filled the ephemeral range with sockets in `TIME_WAIT`, and then `docker run -p 0:8080` could not
   bind — *"address already in use"*, which reads like the subject failing to start. Fixed by reusing
   one connection and by binding a port **below** the ephemeral range instead of asking docker to
   choose inside it.
3. **Two "stop under load" figures were taken with nothing under load.** Native control runs finished
   in ~478 ms with `inFlight=0` and pulled the median below the truth. The oracle has a vacuity guard
   for exactly this; this harness did not. It now excludes such runs and says how many it excluded.

**An earlier run of this harness, before those fixes, reported kore starting 26 % *faster* than the
control on the JVM** — a flattering, consistent, non-overlapping result across five rounds. It did
not survive bounding the poll timeout. The number described the harness.
