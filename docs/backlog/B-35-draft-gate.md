---
id: B-35
title: "Turn on the draft gate on the default branch"
status: done
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

## The audit, which is what this item actually was

Turning the step on is one line. Earning it was re-reading six documents against the code, because
the AC says *active **because its behaviour exists*** and the item itself rejects flipping statuses
to make a checker pass.

**Three features promoted.** `feature-ordered-shutdown`, `feature-health-probes` and
`feature-typed-config` — all built, all exercised on both platforms.

**Two scenarios were already automated and simply lacked their line.** "A participant that hangs does
not consume the drain" is `ShutdownSequenceTest` properties 3 and 5; "a participant that throws does
not abort the sequence" is property 4 plus the every-failure case. The tests were read against the
scenarios' clauses rather than matched by name, and the one clause neither covers — *the process
still exits* — is named as the oracle's, because a unit test with a virtual clock cannot observe a
process.

**One scenario became automatable only now.** "The same value reaches the agents" needed
[B-28](B-28-observability-wiring.md) to exist before anything could be observed receiving a release.
It is covered in two halves — `TracyFlushTest` asserts the `X-Tracy-Release` header tracy **actually
received on the wire**, `VersionRouteTest` asserts what `/version` serves — and the halves are the
same string because `releaseOf` is the only thing that makes one.

**One scenario stays *target*, labelled where it stands.** The JVM booblik flush describes kore's own
participant, which is [B-15](B-15-booblik-adapter.md), blocked on
[B-36](B-36-booblik-adapter-targets.md). The machine already orders consumers before pools; what is
missing is the adapter, not the order. BDD is 37 of 38 and the one is honest.

**And the gate caught something on its first run**, which is the argument for gates. `endpoint-kore-admin`
was still `draft`, and re-reading it found `contract_source` naming `ProbeResponse` and
`VersionResponse` — **two types that were never built**, left from a design that assumed serialised
bodies. Every route answers `text/plain`. Corrected to name the handlers, which are what exists.
