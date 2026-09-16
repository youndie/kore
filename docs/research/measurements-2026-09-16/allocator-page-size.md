# The allocator's page size, and what the published numbers were measuring — B-55, 2026-09-16

Re-measured against the recipes in the portfolio's `native-service-bootstrap` skill. One of the two
moved this subject by more than everything kore adds put together; the other moved nothing, which the
skill predicted and which is why it is written down here rather than adopted.

## What was measured, and on what

| | |
|---|---|
| Subject | `samples/service`, one binary, two arms selected by `--kore=true` |
| kore | branch `feat/sample-allocator-page-size`, the tree this change commits |
| Host | the project's Linux build host — 20 cores, 15 GB, kernel 6.6.87.2, Docker 29.1.3 |
| Repetitions | 15 per cell for the table, 5 per cell for each A/B below |
| Raw output | [`raw-three-numbers.txt`](raw-three-numbers.txt) |

## 1. `MALLOC_ARENA_MAX=2` — no effect, and that is the finding

glibc gives malloc an arena per thread counting host cores, so a service's resident memory can follow
its thread count. The skill carries the recipe **and its counter-example**: on a service without a
database it did nothing, and combined with `-Xallocator=std` it multiplied peak RSS tenfold.

Idle, five runs a cell, `VmRSS` of PID 1 read from the host:

| | runs (kB) | median |
|---|---|---|
| without | 17 440, 17 280, 17 440, 17 760, 17 440 | 17 440 |
| `MALLOC_ARENA_MAX=2` | 17 600, 17 600, 17 120, 17 440, 17 280 | 17 440 |

**Identical medians and fully overlapping spreads.** The sample has no database and opens no pool, so
there is no fan-out of arena-allocating threads for the setting to bound. Not adopted — a line that
does nothing is a line the next reader has to disprove.

## 2. `fixedBlockPageSize=16` — four times, and it is the whole story

The Kotlin/Native runtime keeps a per-thread page cache. Five runs a cell; load is 8 connections
driving `/work?ms=300`, forty requests each, `VmRSS` and the cgroup's `memory.peak` read at the end.

| | idle `VmRSS` | under load `VmRSS` | under load peak | threads |
|---|---|---|---|---|
| without | 17 440 kB | ~109 440 kB | ~109 524 kB | 37–56 |
| `fixedBlockPageSize=16` | **6 560 kB** | **~27 072 kB** | **~32 340 kB** | 37–47 |

**It is not a thread-count difference** — the thread counts overlap. It is the page cache: the same
threads, each holding a smaller block.

**And it is a property of the binary, not of kore.** The control arm moves the same way — ~114 240 kB
against ~27 240 kB under load — so this changes none of the kore-versus-control differences the sample
exists to measure, and all of the absolute numbers.

Adopted as the sample's default. `-PallocatorPageSize=0` rebuilds the variant these figures came
from, which is what keeps them re-checkable.

## 3. The published table was measuring a binary nobody would ship

| image | number | control | kore | difference |
|---|---|---|---|---|
| jvm | time to first `/health` | 973 ms (914–1054) | 986 ms (701–1046) | +13 ms — noise |
| jvm | RSS at ready | 110 548 kB (108 624–111 200) | 116 320 kB (111 520–117 760) | **+5 772 kB (+5%)** |
| jvm | stop under load | 1488 ms (1415–1784) | 3535 ms (3480–3590) | **+2047 ms (+138%)** |
| native | time to first `/health` | 642 ms (623–674) | 645 ms (401–680) | +3 ms — noise |
| native | RSS at ready | **8 800 kB** (8 320–9 440) | **9 280 kB** (8 800–9 760) | **+480 kB (+5%)** |
| native | stop under load | 1458 ms (1421–1492) | 3443 ms (3409–3485) | **+1985 ms (+136%)** |

Against [2026-09-12](../measurements-2026-09-12/three-numbers.md), where native RSS at ready was
**25 920 / 27 520 kB**. Two changes landed between the runs and the drop decomposes across both:
`--as-needed` (#78) dropped three `NEEDED` entries, taking the idle figure to 17 440 kB, and the page
size took it from there to 6 560. The first half is arithmetic across two days and two runs rather
than an A/B, and is offered as such; the second is measured above.

**kore's own cost on native fell with it — +1 600 kB became +480 kB — and that is not an improvement
in kore.** The percentage is unchanged at 5%. What changed is the denominator, which is what a
percentage on an absolute nobody re-measured will do.

The stop column and the in-flight table are unchanged: the control still finishes **none** of the
requests in flight at the signal and drops all of them, both platforms, all fifteen rounds.

## 4. What this says about the number the README publishes

**`RSS at ready` is not the number a container limit is set from.** At ready this subject is 8.8 MB;
under eight connections it touches ~27 MB with the recipe and ~110 MB without it. A reader sizing a
pod from the published figure would set a limit the process walks through on its first burst of
traffic.

The harness samples `VmRSS` once, at the moment the subject first answers — a figure that answers
"how big is it when it starts". Reading the cgroup's `memory.peak` at the end of the load phase
answers "what will the kernel kill it for", and that is the one a chart needs. Not added to the
harness here, because that is a change to what every published cell means rather than a column:
[B-56](../../backlog/B-56-peak-under-load.md).
