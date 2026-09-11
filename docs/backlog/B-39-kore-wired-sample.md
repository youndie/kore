---
id: B-39
title: "The sample wired with kore, and the oracle run that proves the stages"
status: open
priority: P0
size: M
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-09, B-10, B-11, B-17]
---

# B-39 — The sample wired with kore, and the oracle run that proves the stages

Found while picking the next item on 2026-09-12. **B-09, B-10 and B-11 each state acceptance that
none of them can meet**: assertions A1–A5 of research-oracle §2.3, against a running container. Those
need a sample that *uses* kore, and there is not one — `samples/service` is the **control**, and its
whole value is being wired the ordinary way ([B-05](B-05-sample-service.md) keeps it that way
deliberately).

Nothing owned that gap. Each stage item would have been picked, built, and then either closed without
its acceptance exercised or quietly widened to build the missing variant.

- **The decision and its reason.** A second variant of the same sample, wired with kore, run by the
  same oracle against the same scenario. Same shape as the pair that already worked: B-05 built the
  control, B-06 the instrument, B-03 the run. A comparison needs both arms and they are not the same
  item.
- **Rejected:** converting `samples/service` to use kore. That destroys the control, and with it the
  only evidence that kore changes anything — the point [B-05](B-05-sample-service.md) makes in its
  own quirks section.
- **Rejected:** letting each stage item build its own bit of wiring. Three items each half-wiring a
  sample is three ways for the end-to-end assertions to be exercised against something slightly
  different.
- Does **not** cover: the stages themselves, which are B-09, B-10 and B-11.

- AC: `kore-sample:jvm-kore` and `kore-sample:native-kore` run the same oracle scenario as the
  control; A1–A6 pass on both, `--pre-drain` is given so A5 has a subject, and the result goes beside
  the negative control in `docs/research/measurements-<date>/`. A7 — the two platforms agree — is
  asserted by comparing the two runs, which is the first time anything has.
- Anchors: `samples/service/`, `samples/oracle/`, `docs/research/measurements-2026-09-11/negative-control.md`
