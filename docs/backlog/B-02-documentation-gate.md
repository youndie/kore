---
id: B-02
title: "The documentation gate in CI"
status: done
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

## Verified 2026-09-12, on a clean checkout rather than on the working tree

A shallow clone of `main`, `pip install pyyaml`, nothing else:

| Clause | Result |
|---|---|
| `make check` green on a clean checkout with `pyyaml` | **green**, exit 0 |
| the workflow runs exactly that target | `- run: make check` |
| `make report` prints the two non-blocking reports | BDD 29/38, anchors 27 rotten, exit 0 |

**And the gate was made to fail, because one that passes is only half a claim.** Three defects
introduced into the clean checkout one at a time, each caught by name:

| Defect | What the gate said |
|---|---|
| a second item claiming an id already taken | `B-99-duplicate.md: the file name does not match id B-27` |
| a link to a document that does not exist | `FAILED: 1 errors` |
| an `id` no longer equal to its filename | `FAILED: 4 errors` |

Rotten-anchor counts differ between a clean checkout (27) and a working tree with the sibling
repositories beside it (23). That is the report doing its job, not a discrepancy: an anchor into
another repository is unresolvable when that repository is not there, and the report is non-blocking
precisely because a machine cannot tell that from a rename.

## The one thing that was actually wrong

**The Makefile claimed something false about itself.** Its header read *"whatever is not in
`make check` is not a gate"* — while CI's `build` job runs `./gradlew build` and blocks a pull
request. The gate a contributor could not run by name was the slow one, so the way to discover it was
a red pull request, which is the exact failure this item exists to prevent.

`make build` now exists and CI runs `make build GRADLEFLAGS=--no-daemon`, so the command that gates a
pull request and the command a contributor runs are one string in one place. They stay separate
targets because `check` needs python and seconds while `build` needs a toolchain and minutes, and a
single target needing both is one nobody would run.
