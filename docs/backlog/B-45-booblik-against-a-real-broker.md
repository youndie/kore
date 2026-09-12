---
id: B-45
title: "The booblik participant against a real broker"
status: done
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

## The "blocked" entry was wrong, and then the run happened — 2026-09-12

This item spent a day recorded as blocked on a broker image that **already existed**. The check that
produced that entry was an unauthenticated GHCR API call answering `401`; an auth challenge is not
absence, and `docker pull ghcr.io/youndie/booblik:latest` succeeds. The upstream proposal built on it
(§7.3) has been withdrawn. The lesson is the one the table above should have had a column for: a
negative result needs a positive control — some image the same query *does* find.

### What the run found

`samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/BrokerFlush.kt`, three arms against
`ghcr.io/youndie/booblik:latest`, five runs, identical every time
([write-up](../research/measurements-2026-09-12/broker-flush.md)):

| Shutdown | Records read back, of 51 |
|---|---|
| `producer.close()`, then `connection.close()` and `scope.cancel()` in the same breath | **1** — only the awaited warm-up |
| `producer.close()`, 500 ms of quiet, then the teardown | **51** |
| kore's `booblikParticipant(…).stop()` — flush awaited, then close — then the teardown | **51** |

**The AC is met and the premise underneath it is refuted.** The control does lose its batch, so the
treatment means something. But it does not lose it *because `close()` discards records* — the second
arm shows the same `close()` keeping all 51 when anything at all waits afterwards. `close()` sends the
batch on the producer's own coroutine and does not wait; a shutdown is exactly when that coroutine's
scope and connection are being torn down. kore's contribution is the **waiting**, not the sending.
Research §1.8 is amended in place, and so is the CLAUDE.md rule that carried the old mechanism.

### Two preconditions this cost, both invisible until they were checked

- **The broker does not create topics** — `BooblikConfig.topics` is fixed at startup (booblik M-42).
  A produce to an undeclared topic is refused into a handle nobody awaits, and the first *fetch* of
  one costs the connection. The harness declares `BOOBLIK_TOPICS` and awaits the warm-up record, so a
  refusal is a failure rather than an empty fetch that reads like a lost batch.
- **`docker run -p` publishes the port before the process binds it.** The first connection lands on
  docker's proxy and dies the moment it is asked anything. Readiness here is a METADATA round trip
  naming the topic, not an open socket.

- AC: the "a JVM producer's accumulated records are flushed before it is closed" scenario of
  [feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §5 runs against a real broker,
  with a control arm that closes without flushing and loses records — because a run where nothing is
  lost in either arm has measured nothing.
- Anchors: `samples/oracle/`, `kore-booblik/`
