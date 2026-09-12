---
id: B-34
title: "The three numbers, measured as comparisons"
status: done
priority: P2
size: S
stage: m6-release
blocked_by: [B-06]
---

# B-34 — The three numbers, measured as comparisons

Time to first `/health/startup`, RSS at readiness, stop time under load — the measurement plan of
[research-oracle](../research/research-oracle.md) §4.

- **The decision and its reason.** They are measurements, not gates: a threshold in milliseconds
  measures the machine it ran on. Each is reported as *sample against control* — the same binary with
  kore and without it, in the same run, alternating.
- **The rules are the point, and each was paid for once already:** one run per variant is not a
  measurement; the first run after a restart measures warm-up and is discarded explicitly; a ratio
  without an absolute decides nothing; the report names what it measured and on what.
- **Rejected:** putting the figures in the README. A number in prose is one nothing updates. They live
  in `docs/research/measurements-<date>/` and prose links to them.
- Does **not** cover: deciding whether kore is "fast enough". It has no competitor to be fast against;
  what it must not be is a cost nobody noticed.

- AC: a measurements directory with raw output and a one-page summary, taken on both targets, against
  the unfixed control from [B-03](B-03-negative-control.md).
- Anchors: `docs/research/measurements-2026-09-12/`, `samples/oracle/`

## The result

[measurements-2026-09-12/three-numbers.md](../research/measurements-2026-09-12/three-numbers.md), with
the raw output beside it. Median of 5, one discarded warm-up per cell, arms alternating, on both
images.

| number | jvm | native |
|---|---|---|
| time to first `/health` | +12 ms (+1%) | +5 ms (+1%) |
| RSS at ready | +5 760 kB (+5%) | +1 920 kB (+7%) |
| stop under load | +2 028 ms (+137%) | +2 000 ms (+135%) |

**The third number needed a fourth column to mean anything.** The control stops two seconds sooner
because it drops every request it was serving — 40 of 40, both platforms, every run — while kore
finishes all forty. Reported alone, the stop column reads as kore's cost; it is the price of the work
surviving. The harness now records what became of the in-flight requests for exactly that reason.

## Findings

* **The JVM image had been stale since B-26 and nothing could have noticed.** That item gave
  `samples/service` a `version`, which renamed the fat jar; the Dockerfile kept copying the old name,
  which was still present in `build/libs`. A missing file fails `COPY`; a stale one fails nothing, and
  CI does not build images. Fixed by pinning `archiveFileName`, and the measurement harness now
  refuses an image whose kore arm does not serve `/version` — a route that exists only since B-27, so
  its absence dates the image.
* **The harness twice measured itself.** Polling with a fresh connection every 10 ms exhausted the
  ephemeral port range and stopped `docker` binding; a 30 s read timeout applied to a probe poll
  turned one half-open connection into a 16.9 s "time to first `/health`". Both fixed at the source
  rather than by discarding outliers.
* **A flattering result did not survive the fixes.** Before them the harness reported kore starting
  **26 % faster** than the control on the JVM, consistently, across five non-overlapping rounds. It
  was the poll timeout. A pleasant number deserves the same scepticism as an unpleasant one, and it
  got it only because the run was repeated after an unrelated fix.
* **A vacuity guard was missing.** Two native control runs stopped in ~478 ms with nothing in flight
  — a stop time that is not a stop-under-load time. The oracle already guards this; the new harness
  did not, and now does.
