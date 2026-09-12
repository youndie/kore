---
id: B-24
title: "Prefix scoping and the unknown-variable refusal"
status: done
priority: P1
size: S
stage: m4-config
epic: feature-typed-config
blocked_by: [B-22]
---

# B-24 — Prefix scoping and the unknown-variable refusal

A variable under the declared prefix that the schema does not declare fails the start.

- **The decision and its reason.** Half of "production is running something other than what we think"
  is a name that was read differently from how it was written, and nothing today produces an error for
  it. The check is what turns a typo into a refusal.
- **Scoped to a prefix, and the prefix is part of the schema.** A container's environment carries
  `PATH`, `HOSTNAME`, `KUBERNETES_SERVICE_HOST` and every `*_PORT` the kubelet injects. A check over
  the whole environment fails on its first deployment, gets switched off, and is never switched on
  again — so an unscoped version of this feature is a feature that does not exist.
- **Rejected:** a warning instead of a refusal, and an allow-list of ignorable names. The first is
  read once by nobody; the second is a second schema that drifts from the first.
- The case worth testing is the **near-miss**: `SAMPLE_TIMEOUT_MS` declared and `SAMPLE_TIMEOUT_MSEC`
  set. An implementation that only checks required variables passes that one.

- AC: the near-miss in the sample's schema fails the start and the message names both spellings; an
  unrelated `PATH` does not.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/`, `samples/service/`

## Iteration 1 — 2026-09-12

**Done.** The refusal, scoped to the prefix, in `readInto`. 5 tests; 90+ green on both platforms.

**The scenario promised more than the first implementation delivered, and the document won.** It says
*"the message names both spellings"* — and the refusal named only the unknown half, leaving the reader
to find the declared one. So the message now carries a nearest-match suggestion (`did you mean
SAMPLE_WORKERS?`), with a distance bound: four edits is about a plural, a suffix or a transposition,
and beyond that a suggestion stops being a help and becomes a guess that sends the reader to the
wrong variable. A test asserts an unrelated name gets **no** suggestion.

Correcting the code rather than the scenario was the right way round here: the promise was reasonable
and the implementation was simply less than it.

**A target that cannot list the environment contributes nothing** — and does not quietly report
"none". That distinction lives in `EnvironmentNames` (B-22) and is printed by `--print-config`
(B-23); this is where it finally has consequences.

**Mutations, all killed:**

| Mutation | Result |
|---|---|
| drop the prefix scope | red |
| an unlistable target refuses what it cannot see | red |
| the unknown replaces the other problems | red |

The first is what rule 4 exists for: unscoped, `PATH`, `HOSTNAME`, `KUBERNETES_SERVICE_HOST` and every
`*_PORT` the kubelet injects are unknown variables — the process refuses to start on its first
deployment, the check is switched off, and it is never switched on again.

**All nine scenarios of [feature-typed-config](../features/feature-typed-config.md) are now
automated.** Two were held back on the way here because they would have passed **vacuously**: one
until there was an unknown check to survive, one until that check became a refusal rather than a
listing. Coverage goes to 21 of 34.

**Also in this change:** [B-20](B-20-pre-drain-default.md) gained `blocked_by: [B-33]`. The loop
picked it, found its acceptance is a figure measurable only **in a real cluster** — endpoint
propagation through kube-proxy, which a container on a laptop has no Service in front of to measure —
and fixed the dependency rather than producing a number from somewhere else.
