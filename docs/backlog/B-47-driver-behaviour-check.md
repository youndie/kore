---
id: B-47
title: "Check that a pool really does hand out a dead connection"
status: done
priority: P2
size: S
stage: m3-probes
epic: feature-health-probes
blocked_by: []
---

# B-47 — Check that a pool really does hand out a dead connection

[B-40](B-40-sample-pooled-store.md) decided this rather than a store in the sample. The claim under
test is **research §1.9 consequence 1**, which is a derivation and not a verified fact:

> `acquire()` proves a connection object was handed out, which a pool can do from its idle set
> without the server on the far end being alive — that is the check that reports healthy through an
> outage.

Everything kore says about dependency checks rests on it: rule 6 of
[feature-health-probes](../features/feature-health-probes.md), the shape of `storeCheck`, and the
double in `PooledStoreCheckTest` that reproduces it.

- **The decision and its reason.** One driver, one behaviour, one run. Start Postgres, build an
  `sqlx4k` pool, run a statement so the pool holds a live connection, **stop Postgres**, then ask the
  pool for a connection and run a statement through it. The assertion is the difference: `acquire`
  succeeds and the statement fails.
- **Rejected:** asserting only that the statement fails. That would pass against a pool that also
  fails `acquire`, which is the behaviour kore's rule says does *not* happen — and a test that cannot
  distinguish the two has not checked the claim.
- **Rejected:** running it inside the oracle or in `make build`. It needs a container, it answers the
  same question every time, and a check that makes the ordinary gate slower is one people learn to
  skip.
- **If the driver does not behave this way**, that is the more valuable outcome: §1.9's consequence 1
  is amended at the point of divergence, rule 6's justification moves, and `PooledStoreCheckTest`'s
  double is wrong and says so.

- AC: the check runs by name against a real Postgres on the JVM; it asserts both halves — `acquire`
  succeeding and the statement failing — after the server is stopped; the result is recorded in
  research §1.9 whichever way it goes.
- Anchors: `samples/oracle/`, `docs/research/research-architecture.md`

## The result — supported, once the experiment asked the right question

Against a real Postgres with `sqlx4k-postgres` 1.13.0
([raw](../research/measurements-2026-09-12/raw-driver-behaviour.txt)):

| how the server went away | `acquire()` | a statement |
|---|---|---|
| a clean `stop` | **fails** | fails |
| **paused** — frozen, sockets open, nothing answering | **succeeds** | **hangs**, cut off at a 10 s bound |

Research §1.9 consequence 1 stands, **for the silent case**, which is the case it is about.

## The experiment was wrong first, and that is the part to keep

The first version stopped the container cleanly and reported the consequence **refuted**. It was
measuring a different question: a clean shutdown closes the connections and the client is *told*, so
the pool fails `acquire` too. The container helper's own comment claimed a clean shutdown is "the
case a pool is least likely to notice" — it is the case it notices most easily, because the peer
sends `FIN`.

A refutation from the wrong experiment would have been expensive: three documents point at that
consequence, and "measured" would have carried it further than "derived" ever did.

## And the confirmation came with a correction

In the silent case the statement does not fail — it **hangs**. `PooledStoreCheckTest`'s double is
faithful about the asymmetry *and* about that shape, because it covers both: one case throws, one
case `awaitCancellation()`s and is cut off by the check's own timeout. That was luck rather than
foresight, and it is now the reason rule 4's timeout is described as load-bearing.
