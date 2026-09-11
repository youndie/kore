---
id: B-18
title: "The pooled-store check runs a statement, not an acquire"
status: open
priority: P1
size: S
stage: m3-probes
epic: feature-health-probes
blocked_by: [B-16]
---

# B-18 — The pooled-store check runs a statement, not an acquire

A readiness check for a connection pool.

- **The decision and its reason.** Research §1.9: sqlx4k's `ConnectionPool` offers `poolSize()`,
  `poolIdleSize()`, `acquire()` and `close()` — and no `ping`. `acquire()` on its own proves a
  connection object was handed out, which a pool can do from its idle set while the server on the far
  end is gone. That is the check that reports healthy through an outage.
- **Rejected:** `poolIdleSize() > 0`. It is cheaper and it is a statement about the pool's bookkeeping.
- Does **not** cover: whether a trivial statement is a good proxy for "this database will serve my
  queries". That is a question for the service that owns the query; kore runs checks and reports them.
- The pool's `close()` is `suspend` and returns a `Result`, so the release-stage participant composes
  without a thread hand-off and a failed close is a value rather than a throw on a shutdown path.

- AC: the "a pool that hands out a dead connection is not healthy" scenario of feature-health-probes
  §7 holds against a store that has been stopped underneath the process.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/`
