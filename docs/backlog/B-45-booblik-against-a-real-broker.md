---
id: B-45
title: "The booblik participant against a real broker"
status: question
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

## Blocked on something that does not exist — 2026-09-12

Picked by the rule and stopped at the first step: **there is no broker to run it against.**

| Looked for | Found |
|---|---|
| a published `booblik-app` (the server) | **no** — the portfolio's repository carries `booblik-client`, `booblik-core`, `booblik-java`, `booblik-native*` and `booblik-protocol*`, and no server |
| the same on Maven Central | **no** — same list, minus the two that are portfolio-only |
| a published container image | **none reachable**; booblik has a `Dockerfile` at its root, so an image *can* be built, but nothing publishes one |

**Rejected: cloning and building booblik inside this check.** It would work — the build host has git,
network and docker — and it would make a kore check depend on building another repository at some
commit, breaking whenever that build changes and reproducible from nothing in this repository. A test
that needs a second project's build to be green is a test that reports on that project.

So this waits on a **published broker image** (or a published `booblik-app` the check could run with
`java -jar`). Raised as §7.3 of the upstream proposals: the `Dockerfile` exists and nothing publishes
its output, which is one CI step away.

**What this does not block.** [B-15](B-15-booblik-adapter.md) is done and its five tests assert kore's
half — the order — on both platforms. What waits here is the other half, which belongs to booblik:
that a flush actually puts records on a socket and that closing without one loses them.

- AC: the "a JVM producer's accumulated records are flushed before it is closed" scenario of
  [feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §5 runs against a real broker,
  with a control arm that closes without flushing and loses records — because a run where nothing is
  lost in either arm has measured nothing.
- Anchors: `samples/oracle/`, `kore-booblik/`
