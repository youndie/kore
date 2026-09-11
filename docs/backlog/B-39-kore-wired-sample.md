---
id: B-39
title: "The sample wired with kore, and the oracle run that proves the stages"
status: done
priority: P0
size: M
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-09, B-10, B-11, B-17]
---

# B-39 — The sample wired with kore, and the oracle run that proves the stages

> **Taken out of numeric order on 2026-09-12, ahead of B-21, and the reason is recorded here rather
> than left to look like a whim.** B-09, B-10 and B-11 were each closed on acceptance that was
> deliberately *narrowed*, with their end-to-end halves moved here — so three merged items currently
> have unexercised acceptance and this item owns the debt. Everything it needs now exists. Going on to
> M4 first would mean building the configuration feature on top of a shutdown sequence that has never
> been run end to end against a real signal.

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

## Iteration 1 — 2026-09-12

**Done, and it collects the debt three merged items left.** The same sample wired with kore, run by
the same oracle against the same scenario:
[the matrix](../research/measurements-2026-09-11/kore-wired.md), 24 runs, every cell identical across
its three repetitions.

**The claim, demonstrated.** The same service, closing the same resource, on the same signal — and
the only difference is *when*. In `ApplicationStopping` it runs before the drain on Kotlin/Native and
breaks **48 requests**; in kore's release stage it runs after, and breaks **none**. Research §1.1 as
a before and an after rather than a reading of two source files.

**Zero not-applicable, for the first time.** Against the control, A3, A4 and A5 report
`NOT_APPLICABLE` — it refuses nothing and has no readiness endpoint. The kore runs are 9 of 9 with
nothing skipped, which is the number that says the instrument finally has a subject for every
question it asks. **A7 — the two platforms agree — is satisfied for the first time.**

**The trap it nearly fell into, again.** The first version of the treatment arm **closed nothing**.
It passed 9 of 9 on both platforms and proved nothing: an arm that does not do the thing cannot show
that doing it at the right time is safe. That is the third time in this repository an experiment
silently measured nothing, and the **second** time the cause was an arm with no consequence — the
negative control had the same defect before its third cell existed. The pattern now has a name in the
record: *when a comparison passes, check that both arms actually do the thing.*

**Four scenarios of [feature-ordered-shutdown](../features/feature-ordered-shutdown.md) become
`**Automated:**`**, all four through a real container taking a real `SIGTERM`. Overall BDD coverage
goes from 6 of 34 to **10 of 34**.

**What is deliberately not claimed:** kore's rows are faster (15.5 s and 17.5 s against 20.5 s) and
that is *not a result* — the arms have different budgets. Comparing them would compare two
configurations rather than two designs. A6 is the only timing claim: kore stayed inside the grace
period it was given.

**Also here:** the sample now answers open question 2 of the research in the only way that counts,
by showing what it costs. Every line of the wiring is a *consumer* writing wiring; no part of it
lives in the library. That is the current answer to "does kore own the entry point" — it does not —
and [B-30](B-30-entry-point-question.md) can now be decided against something real instead of in the
abstract.
