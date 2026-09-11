---
id: B-13
title: "The property test over the stop order, proved by mutation"
status: done
priority: P0
size: M
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-04]
---

# B-13 — The property test over the stop order, proved by mutation

The six properties of [research-oracle](../research/research-oracle.md) §3.2, over generated
durations, failures and participant counts, on every target kore publishes.

- **The decision and its reason.** A property test over a sequence is easy to write so that it holds
  for any sequence. So the test is itself under test: the six mutations named in research-oracle §3.3
  are applied and the suite **must** go red for each. A mutation that survives is a gap in the
  property, and the item is not done until it fails.
- **Rejected:** scripted ordering tests. They cover the orderings somebody thought of, which is the
  set that was already in the implementer's head.
- Does **not** cover: the pre-drain wait set to zero — a property test cannot see a legal duration.
  That gap is named in research-oracle §3.3 and covered by assertion A5 in the end-to-end oracle
  instead. Both gates exist because neither replaces the other.

- AC: every property holds; every named mutation fails the suite; the mutation run is recorded in
  this item.
- Anchors: `kore-core/src/commonTest/kotlin/io/github/youndie/kore/lifecycle/`

## Iteration 1 — 2026-09-12

**Done.** Six properties over 200 generated plans, on `jvm` and `linuxX64`. 30 tests green on each.

**The generator is hand-written rather than a library**, and the reasons are specific: the generated
thing is a *plan*, so shrinking has nothing useful to shrink towards; the seed is printed on failure,
which is the affordance that actually matters; and a dependency here would need its native targets
checked in the registry before it could live in a common test source set. It deliberately produces
stages with **no** participants and participants that **cannot** finish in time, because the
interesting orderings are at the edges and a generator that only produces plausible plans tests the
cases somebody already thought of.

**The clock is virtual.** Generated durations reach tens of seconds; in real time this suite would
take hours and would be measuring the machine.

**It passed on the first run, and that is only a claim about the implementation because of the
mutations:**

| Mutation | Against the generated plans |
|---|---|
| reverse the specified order | 13 of 30 red |
| join the stage's scope instead of detaching it | 3 of 30 red, **including two of the generative properties by name** |
| a throwing participant aborts the sequence | red |
| remove the once-only guard | red |
| a DWELL stage skips its wait | 1 of 30 red |

Each was checked to be a **test** failure rather than a compilation failure, which is not the same
thing and is easy to mistake for a caught mutant when the only signal read is `BUILD FAILED`.

The second row is the one worth keeping: the detach — refusing to *wait* for a participant past its
deadline rather than being able to stop one — is the design decision here that is easiest to argue
away, and it is the generative properties rather than a hand-written example that refuse the
alternative.

**The gap named in advance is still the gap.** A property test over the machine cannot see a
*duration* set to zero, because zero is a legal duration; the pre-drain wait being deleted is
assertion A5 of the end-to-end oracle and nothing here. research-oracle §3.3 said so before this was
written, and it is still true after.
