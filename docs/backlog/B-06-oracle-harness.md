---
id: B-06
title: "The oracle harness: load, signal, assertions"
status: open
priority: P0
size: L
stage: m1-oracle
epic: feature-ordered-shutdown
blocked_by: [B-05]
---

# B-06 — The oracle harness: load, signal, assertions

The load driver and the assertions A1–A7 of [research-oracle](../research/research-oracle.md) §2.3,
plus the three vacuity guards of §2.5.

- **The decision and its reason.** Every assertion is made from the **client's** record and the
  process's exit, never from the server's log. A log line is written by the code under test, and an
  oracle that trusts it can be satisfied by a comment.
- **Rejected:** asserting that the server closed the socket. Research D6: on CIO that would pass
  because of the 45-second idle timeout or the grace-period cancellation — for a reason unrelated to
  the code under test.
- Does **not** cover: multiple replicas, `SIGKILL`, or timing thresholds. Wall-clock numbers are
  [B-34](B-34-the-three-numbers.md), measured as comparisons.
- The vacuity guards are part of this item, not a follow-up: a run where nothing was in flight must
  report **inconclusive**, not pass.

- AC: the harness fails when nothing was in flight at the signal; it fails when the process never
  saw the signal; it distinguishes a `500` from a `503`; it records response headers per request.
- Anchors: `samples/oracle/`
