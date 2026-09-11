---
id: B-23
title: "--print-config: values, origins, masks, and the probe block"
status: done
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

## Iteration 1 — 2026-09-12

**Done.** `printConfig`, `ConfigSchema.attempt`, and `koreProbeBlock` in `kore-ktor`. 8 tests; 77
green on `linuxX64`, 72 on `jvm`.

**It prints what it resolved even when the configuration is unusable**, and that required splitting
the reader: `read` throws, `attempt` does not. A flag that printed nothing on failure would be
useless in exactly the case it exists for — the question *what does this deployment think it is
configured as* is asked most often **because** the process will not start.

**The unknown-variable line is printed always**, not only when the check is unavailable. A deployment
reading "no unknown variables found" has to be able to tell that from "nothing was looked at", and
the only way to be sure is for the check to state its own availability every time. A test asserts an
unlistable target does **not** print "none".

**The probe block lives in `kore-ktor`** and reaches the printer as an extra section, which is how
`kore-core` prints it without knowing what an HTTP route is.

**Mutations, all killed:**

| Mutation | Result |
|---|---|
| an unlistable target reports "none" | red |
| an unusable configuration exits zero | red |
| secrets rendered in the clear | red |
| the unknown check ignores the prefix | red |

The last is the one rule 4 exists for: without the prefix scope, `PATH` and every `*_PORT` the
kubelet injects would be reported as unknown, the check would fail on its first deployment, be
switched off, and never be switched on again.

**Four scenarios become `**Automated:**`; coverage goes from 14 of 34 to 18 of 34.** One of them —
"a variable outside the prefix is not the schema's business" — was deliberately held back at B-21
because it would have passed **vacuously**: there was no unknown check for it to survive. There is
one now.
