# The three numbers — [B-34](../../backlog/B-34-the-three-numbers.md), 2026-09-12

> **Superseded on 2026-09-16 for the absolute figures, not for the method.**
> `samples/service` was built without the allocator page size this portfolio sets for every
> Kotlin/Native service, so the native RSS column here — 25 920 / 27 520 kB — describes a binary
> nobody would ship. Re-taken at 8 800 / 9 280 kB in
> [allocator-page-size.md](../measurements-2026-09-16/allocator-page-size.md). Everything below
> about *how* to take the measurement, and the four things this harness got wrong first, stands.

The measurement plan of [research-oracle](../research-oracle.md) §4, taken as comparisons: the same
binary with kore and without it, alternating, in one run.

## What was measured, and on what

| | |
|---|---|
| Subject | `samples/service`, one binary, two arms selected by `--kore=true` |
| Images | `kore-sample:jvm` (`eclipse-temurin:25-jre`), `kore-sample:native` (`gcr.io/distroless/cc-debian13`, `linuxX64` release) |
| kore | branch `feat/b-50-sample-config`, the tree this change commits — the images were rebuilt from it after the last source edit, because a number taken before a refactor is a number about a different binary |
| Host | the project's Linux build host — 20 cores, 15 GB, kernel 6.6.87.2, Docker 29.1.3 |
| Load | closed loop, 8 keep-alive connections on `/work?ms=3000` |
| Repetitions | **15 per cell**, plus one warm-up per cell that is discarded and printed as discarded |
| Grace | 20 s given to the harness; the control's own Ktor grace is its default 1000 ms |
| Raw output | [`raw-three-numbers-with-config.txt`](raw-three-numbers-with-config.txt) — the first run, at n=5 and against a sample that read no configuration, is kept as [`raw-three-numbers.txt`](raw-three-numbers.txt) |

`./gradlew :samples:oracle:measure --args="--repeats=15 --work=3000 --connections=8"`

**Re-measured for [B-50](../../backlog/B-50-sample-uses-the-config-schema.md), and the subject
changed.** The kore arm now reads a declared configuration schema from the environment — the fifth
feature, which until B-50 no running binary exercised — so it does strictly more work than the arm
these numbers were first taken against. The harness passes `SAMPLE_POOL_DSN` to that arm and nothing
to the control, because a service written without kore has nowhere to read one from.

**And n went from 5 to 15, because the first re-run disagreed with itself.** Two runs of five put
native RSS at −320 kB and at +2 400 kB. A difference whose sign depends on which five runs you took
is not a difference, and the answer is more samples in one alternating sequence rather than a
prettier pair of them.

**The binary cannot name its own commit here.** `/version` reports `unknown`, because the build host
holds no VCS metadata, so the commit above is this working tree's and not the container's word for
it. That is a property of where the images are built, not of the identity mechanism — which is
[feature-build-identity](../../features/feature-build-identity.md)'s documented degradation.

## The numbers

Median (min–max), n=15.

| image | number | control | kore | difference |
|---|---|---|---|---|
| jvm | time to first `/health` | 1171 ms (997–1243) | 1201 ms (1030–1353) | **+30 ms (+3%) — noise, see below** |
| jvm | RSS at ready | 111 180 kB (110 544–111 680) | 116 788 kB (111 792–117 904) | **+5 608 kB (+5%)** |
| jvm | stop under load | 1491 ms (1441–1526) | 3498 ms (3476–3583) | **+2007 ms (+135%)** |
| native | time to first `/health` | 727 ms (474–1098) | 717 ms (488–949) | **−10 ms (−1%) — noise, see below** |
| native | RSS at ready | 25 920 kB (24 960–26 880) | 27 520 kB (26 080–29 440) | **+1 600 kB (+6%)** |
| native | stop under load | 1523 ms (1463–2055) | 3499 ms (3460–3621) | **+1976 ms (+130%)** |

kore's own `/health/startup` — which the control has no equivalent of, so it is reported alone and
not as a comparison — first answered `200` at **1205 ms (1033–1360)** on the JVM and **720 ms
(490–951)** on native.

### The startup difference is noise, and this is what that looks like

Four measurements of the same quantity, on the same host, same harness:

| Run | jvm | native |
|---|---|---|
| n=5, the sample before it read a configuration | **+12 ms** | **+5 ms** |
| n=15, first | **−23 ms** | **−27 ms** |
| n=15, second — the table above | **+30 ms** | **−10 ms** |

**The sign flips in both directions.** The medians move by tens of milliseconds while the spread
inside a single arm is 250 ms on the JVM and over 600 ms on native — the JVM's fastest control start
here was 997 ms and its slowest 1243. What varies between these rows is the build host under fifteen
back-to-back container starts, not the subject.

So the honest reading is **kore's startup cost does not separate from the noise on either platform**,
and it is deliberately weaker than what this document said first. The original table reported
**+12 ms (+1 %)** and **+5 ms (+1 %)** as a cost "inside the spread of either arm". They were inside
the spread — and, on this evidence, they were the same noise that later came out negative. A figure
small enough to be noise has to be published as noise: the plus sign is what a reader remembers, and
here it was an artefact of which afternoon the run happened on.

### The third number is not a cost, and reporting it without the next table would be a lie

| image | arm | requests spanning the signal | finished | dropped |
|---|---|---|---|---|
| jvm | control | 120 | 0 | **120** |
| jvm | kore | 120 | **120** | 0 |
| native | control | 119 | 0 | **119** |
| native | kore | 120 | **120** | 0 |

**The control stops two seconds sooner because it drops every request it was serving.** All of them,
on both platforms, in every one of the fifteen rounds — and kore finishes all of them, in every one.
The +135 % is the price of the work not being thrown away, and a reader shown only the stop column
would read the wrong sign.

(119 rather than 120 once: a round whose eighth connection had not begun its request when the
signal landed contributes seven. The harness counts what spanned the signal rather than what it
intended to send, which is the difference between a measurement and a plan.)

This is the negative control of [2026-09-11](../measurements-2026-09-11/negative-control.md) reproduced
from a different harness: an unconfigured Ktor service cuts off in-flight work at its 1000 ms default
grace, on both platforms, with no ordering involved.

## What the other two say

**Startup: no measurable difference.** See above — both cells came out negative and the sign flips
between runs. kore is not free: it installs two plugins, a route, and now reads a configuration
schema. None of that separates from the noise of starting a container on this host.

**RSS costs 1.6–5.6 MB.** +5.6 MB on the JVM, +1.6 MB on native; as a fraction, 5 % and 6 %. Both
halves are reported because neither decides anything alone: 6 % sounds larger than 1.6 MB reads, and
1.6 MB sounds smaller than 6 % of a 26 MB resident set.

**This is the only cost that survives more samples, and it survived the subject growing.** Across the
three runs tabulated above, the JVM figure was +5.8, +6.4 and +5.6 MB and native was +1.9, +1.3 and
+1.6 MB — a band a few hundred kilobytes wide, holding its sign, while the startup column changed
sign twice. That contrast is the argument for reporting one of them and not the other.

## Four things the harness got wrong first, and how they were caught

Recorded because each produced a plausible number that was not a measurement.

0. **Five rounds were not enough to notice.** The first re-measurement for B-50 put native RSS at
   −320 kB; a second run of the same five put it at +2 400 kB. Neither was wrong — both were five
   samples of a quantity whose spread is wider than the difference being read out of it. The
   published figure is one alternating run of fifteen, and the two disagreeing fives are the reason
   it is fifteen.

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
