---
id: B-45
title: "The booblik participant against a real broker"
status: open
priority: P2
size: M
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: []
---

# B-45 — The booblik participant against a real broker

[B-15](B-15-booblik-adapter.md) asserts kore's half of the scenario — **the order**: flush first,
bounded, then close, always. What it does not assert is the half that belongs to booblik: that a
flush actually puts the accumulated records on a socket, and that closing without one loses them.

That is deliberate rather than a shortcut. Testing it needs a **real broker**, because the claim is
about a client's behaviour against a server, and a double that reproduced the loss would be asserting
the thing it was written to reproduce.

- **The decision and its reason.** The oracle already drives containers with `docker`, so a booblik
  container is the same shape of harness rather than a new one. The assertion is the difference the
  adapter exists for: against the **JVM** client, a producer closed without a flush loses the batch and
  a producer closed through kore's participant does not.
- **Rejected:** asserting it against the native client only because that is where the sample runs.
  The native client already sends on close, so the run would pass whether or not kore flushed —
  a green result that proves nothing, which this repository has produced before and written down.
- Does **not** cover: the JVM/native difference disappearing. If
  [youndie/booblik#68](https://github.com/youndie/booblik/issues/68) is fixed, this run should keep
  passing for a different reason, and that is worth seeing rather than assuming.

- AC: the "a JVM producer's accumulated records are flushed before it is closed" scenario of
  [feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §5 runs against a real broker,
  with a control arm that closes without flushing and loses records — because a run where nothing is
  lost in either arm has measured nothing.
- Anchors: `samples/oracle/`, `kore-booblik/`
