---
id: B-24
title: "Prefix scoping and the unknown-variable refusal"
status: open
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
