---
id: B-34
title: "The three numbers, measured as comparisons"
status: wip
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
- Anchors: `docs/research/`, `samples/oracle/`
