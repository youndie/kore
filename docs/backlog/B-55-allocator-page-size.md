---
id: B-55
title: "Re-measure the published numbers against the runtime recipes"
status: done
priority: P1
size: M
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-55 — Re-measure the published numbers against the runtime recipes

The portfolio's `native-service-bootstrap` skill carries two allocator recipes for a Kotlin/Native
service, and `samples/service` applied neither. The README's numbers were therefore taken from a
binary built the way nobody in this portfolio ships one.

Full report: [allocator-page-size.md](../research/measurements-2026-09-16/allocator-page-size.md).

## What the two recipes bought

| Recipe | Effect on this subject |
|---|---|
| `MALLOC_ARENA_MAX=2` | **nothing** — identical medians, overlapping spreads. Not adopted |
| `fixedBlockPageSize=16` | idle 17 440 → **6 560 kB**; under load ~109 → **~27 MB**. Adopted |

The skill predicted the first: it carries the counter-example that on a service without a database the
setting did nothing. This sample has no database. **A recipe that does nothing here is worth writing
down as measured rather than adopted**, or the next reader has to disprove a line that was copied
because it was in a list.

The second is the largest single number in this sample's memory profile — four times everything kore
adds put together — and it is a property of the **binary**, not of kore: the control arm moves the
same way. So it changes none of the kore-versus-control differences the sample exists to measure, and
all of the absolute ones.

## The published table was wrong by 3× on native

`RSS at ready` went from **25 920 / 27 520 kB** to **8 800 / 9 280 kB**, and kore's own cost from
+1 600 kB to **+480 kB** at the same +5%. Part of the drop is `--as-needed` (#78), which landed
between the runs; the decomposition is in the report and the half that is arithmetic across two days
says so.

**The percentage did not move, and that is the lesson in one line.** A percentage against an absolute
nobody re-measures stays plausible while the absolute goes stale underneath it.

## What was deliberately not done

`RSS at ready` is not the number a container limit is set from — at ready this subject is 8.8 MB and
under eight connections it touches ~27 MB, or ~110 MB without the recipe. The harness samples `VmRSS`
once, when the subject first answers. Reading the cgroup's `memory.peak` at the end of the load phase
is the figure a chart needs, and it changes what every published cell means rather than adding a
column: [B-56](B-56-peak-under-load.md).

- AC: both recipes measured on this subject, adopted or refused with the measurement beside the
  decision. **Met.**
- AC: the published tables re-taken on the changed binary and replaced in the same change. **Met** —
  README and the 2026-09-12 write-up, which keeps its own numbers and gains a pointer.
- Anchors: `samples/service/build.gradle.kts`, `docs/research/measurements-2026-09-16/`
