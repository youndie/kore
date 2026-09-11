---
id: B-18
title: "The pooled-store check runs a statement, not an acquire"
status: done
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

## Iteration 1 — 2026-09-12

**Done.** `storeCheck` in `kore-core`, and the test that demonstrates why it is shaped that way.

**kore takes no driver dependency**, so what it can offer is the right *shape* — and the shape is the
whole content of the rule. The demonstrating test runs both shapes against the **same pool in the
same state**: the `acquire`-shaped check passes and the statement-shaped one fails. An `acquire` check
is not a weaker check, it answers a different question, and the difference is invisible until the far
end is gone.

**The store choice B-05 deferred here arrived with a constraint nobody had noticed: SQLite cannot
demonstrate this at all.** It is embedded — there is no server behind the pool to stop — so the
portfolio's obvious native candidate is the one store that cannot be in the state the scenario
describes. `sqlx4k-postgres` publishes `-jvm` and `-linuxx64` (checked in Central), so a networked
store is possible; but putting one in the sample means a second container in every oracle run, and
that is [B-40](B-40-sample-pooled-store.md), a question rather than a decision I take.

**Mutations, both killed:**

| Mutation | Result |
|---|---|
| the check swallows the statement's failure | red in 2 s |
| the registry ignores the per-check timeout | red after **3 m 20 s** |

The second is worth reading: it did not fail an assertion, it **hung** until the test framework gave
up. That is the timeout's purpose demonstrated rather than asserted — without it a check that never
returns is a suite that never finishes, and in production a probe that never answers.

**The last scenario of [feature-health-probes](../features/feature-health-probes.md) becomes
`**Automated:**`, so all six are** — with the caveat written on the scenario itself: it runs against a
double that reproduces the documented failure, not a real driver. Coverage goes to 20 of 34.
