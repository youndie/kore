---
id: B-23
title: "--print-config: values, origins, masks, and the probe block"
status: open
priority: P0
size: S
stage: m4-config
epic: feature-typed-config
blocked_by: [B-21]
---

# B-23 — --print-config: values, origins, masks, and the probe block

A flag that prints the resolved configuration and exits without serving.

- **The decision and its reason.** The question is asked most often *because* the process will not
  start, so it must not need a process that started. A flag works in CI, in a `kubectl run` against
  the image, in the one-shot migration container the portfolio already runs, and on a laptop; a route
  works in one of those and not in the case that matters.
- **Where each value came from is part of the answer** — `default`, `env` or `code`. "The default was
  used" and "the environment said the same thing as the default" are different facts, and the
  difference is what a renamed variable looks like.
- Masking is a property of the declared field, not a list of names somebody keeps in sync.
- It also prints the probe block of [feature-health-probes](../features/feature-health-probes.md) §6
  and the unknown-variable capability of this target, so a consumer does not have to find either file.

- AC: output shows every declared key with value, origin and mask; secrets never appear; the command
  exits non-zero when the configuration is invalid, with the same message the start would give.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/`
