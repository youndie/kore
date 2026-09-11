---
id: B-12
title: "Refuse at startup a sequence that cannot fit the grace period"
status: open
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
