---
id: B-35
title: "Turn on the draft gate on the default branch"
status: open
priority: infra
size: XS
stage: m6-release
blocked_by: [B-13, B-17, B-23, B-27, B-28]
---

# B-35 — Turn on the draft gate on the default branch

`python3 scripts/docs_check.py --on-main` makes `status: draft` an error on the default branch, which
is the mechanical half of "`main` describes what exists".

- **What is wrong today.** It cannot pass. Every feature document in this repository is a draft,
  correctly: the code does not exist. The repository's `main` currently carries a plan, and the gate
  is off with this item as its address.
- **The decision and its reason.** Off with a dated debt, rather than relaxed. A guard that is
  permanently weakened is one nobody remembers was ever meant to be strong; a guard with an item
  blocking on the work that makes it passable is one that turns on by itself when the work lands.
- **Rejected:** landing the documentation in a long-lived pull request instead. It is the shape the
  rule was written for, and it makes the documentation unreadable in `main` for months — which defeats
  the reason the documentation was written first.
- **Rejected:** marking the features `active` now. That is the lie the rule exists to prevent.

- AC: every feature document is `active` because its behaviour exists; the `--on-main` step is
  uncommented in `.github/workflows/check.yaml`; the default branch is green on it.
- Anchors: `.github/workflows/check.yaml`, `docs/features/`
