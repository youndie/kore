---
id: B-12
title: "Refuse at startup a sequence that cannot fit the grace period"
status: done
priority: P1
size: S
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-11]
---

# B-12 — Refuse at startup a sequence that cannot fit the grace period

The stage deadlines sum to something; the grace period is something. When the first exceeds the
second, the process is killed mid-drain.

- **The decision and its reason.** A `SIGKILL` in the middle of a drain looks exactly like a crash in
  a log, and the cause is a number somebody raised in one place. Refusing at startup moves the
  failure to the moment somebody is looking at it.
- **Rejected:** a warning. Research Risk 4: a default that is wrong is worse than none because nobody
  reads it again, and a warning at startup is read exactly once, by nobody.
- Open, and it is the real content of this item: **what kore should assume when it is not told the
  grace period**, which is the common case outside Kubernetes. Leading hypothesis: assume the
  Kubernetes default of 30 s and say so in `--print-config`. Settled here or in
  [B-25](B-25-undeclared-grace-period.md), not in both.

- AC: the "sequence that cannot fit" scenario of feature-ordered-shutdown §5 holds; `--print-config`
  shows the sum beside the grace period and where each came from.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/`

## Iteration 1 — 2026-09-12

**Done.** The fit check in `ShutdownDeadlines`, and `describe()` for `--print-config`. 7 tests; 83
green on `linuxX64`, 78 on `jvm`.

**The check found an over-budget configuration in this repository's own test suite on its first
run** — a 31 s sequence against a 30 s grace period, in the test that asserts the announce stage
waits. That is the cheap place for a budget to be wrong, and it is the first thing the check did.

**The boundary is `<=`, not `<`.** A sequence that uses its whole budget has not overrun it, and
refusing it would make the printed sum a lie by one second. A mutation to `<` is red.

**The message names both numbers**, because the reader has to see which of the two they got wrong
without opening the source — and it says what to change.

**Mutations, all killed, and the tests confirmed by name in the result file:**

| Mutation | Result |
|---|---|
| drop the fit check | red |
| the boundary becomes strict | red |
| an assumed grace period claims to be declared | red |

**[B-25](B-25-undeclared-grace-period.md) is deliberately still open.** This item had to do
*something* when it is not told the grace period, so it runs that item's leading hypothesis —
assume the Kubernetes default, and **print that it assumed**. That is the hypothesis executing so the
rest of the library could be built, not the question being answered. Changing it is a small change in
one constructor, which is the right size for a decision that belongs to somebody else.

One scenario becomes `**Automated:**`; coverage goes from 18 of 34 to 19 of 34.
