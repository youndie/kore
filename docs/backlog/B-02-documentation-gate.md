---
id: B-02
title: "The documentation gate in CI"
status: wip
priority: infra
size: S
stage: m0-shape
---

# B-02 — The documentation gate in CI

The documentation exists before the code, which means it is the only thing a gate can check for a
while — and a documentation tree with no gate drifts from the day it is written.

- **The decision and its reason.** CI runs `make check`, the same target a contributor runs. A local
  set that differs from the CI set turns "green here, red there" into the normal state of affairs,
  and then neither is read.
- **Rejected:** path filters on the workflow. They save seconds and buy red default branches — a
  generated index diverges from its sources when something outside the listed paths moves, and a new
  directory is a list nobody remembers to update.
- Does **not** cover: the `--on-main` draft gate, which cannot pass while every feature is a draft.
  That is [B-35](B-35-draft-gate.md), and it is a dated debt rather than a relaxed rule.

- AC: `make check` is green on a clean checkout with `pyyaml` installed; the workflow runs exactly
  that target; `make report` prints the two non-blocking reports.
- Anchors: `Makefile`, `.github/workflows/check.yaml`, `scripts/`
