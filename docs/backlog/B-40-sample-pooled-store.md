---
id: B-40
title: "Give the sample a real pooled store the oracle can stop underneath it"
status: done
priority: P2
size: M
stage: m3-probes
epic: feature-health-probes
---

# B-40 — Give the sample a real pooled store the oracle can stop underneath it

Found while doing [B-18](B-18-pooled-store-check.md), and it is the store choice
[B-05](B-05-sample-service.md) deferred — arriving with a constraint nobody had noticed.

**SQLite cannot demonstrate the scenario at all.** It is embedded: there is no server behind the pool
to stop, so "a pool hands out a connection whose far end is gone" is not a state it can be in. The
portfolio's native services all use `sqlx4k-sqlite`, which is why it was the obvious candidate and
why the obviousness was misleading.

What is available, checked in Central rather than assumed: `sqlx4k-postgres` publishes **both**
`-jvm` and `-linuxx64` at 1.13.0, as does `sqlx4k-sqlite`. So a networked store on both arms is
possible.

**Why this is a question rather than an item to take.** It is not a line of code, it is a second
container in the oracle run:

- the harness must start Postgres, wait for **its** readiness, and only then start the subject —
  a dependency the oracle currently does not have at all;
- every oracle run gets slower, and the negative control is 24 runs;
- stopping the store underneath a running subject is a new capability the harness needs;
- and the sample stops being the smallest thing that answers the question, which is what
  [sample-service](../services/sample-service.md) says every line of it has to be.

The alternatives, all defensible:

- **take it** — the only way the acquire-versus-statement difference is demonstrated against a real
  driver rather than a double;
- **leave it** — `PooledStoreCheckTest` reproduces the documented failure faithfully, and what kore
  owns is the check's *shape*; whether a given driver behaves as described is that driver's business;
- **narrow it** — a real Postgres in a test of `kore-core` alone, never in the oracle, so the harness
  stays single-container.

## Decided 2026-09-12: narrow it

**Not the sample, and not the oracle run. A separately-invoked check against a real Postgres, once.**

### What is actually unverified, which is smaller than the item assumed

Research §1.9 verified sqlx4k's *API* by reading its sources: `ConnectionPool` has `acquire`,
`poolSize`, `poolIdleSize`, `close`, and no `ping`. That is a fact with an address.

Its **consequence 1** is not: *"`acquire()` proves a connection object was handed out, which a pool
can do from its idle set without the server on the far end being alive"*. That is a claim about how
sqlx4k behaves after the server dies, derived rather than observed — and it is the whole
justification for rule 6 and for `storeCheck`'s shape.

So the thing worth a container is **one behaviour of one driver**, not a store in the sample. It does
not change per oracle run, and it does not need the subject to be running at all.

### Why not the other two

**Taking it** puts Postgres in the oracle run: the harness would wait for the store's readiness before
starting the subject, gain a capability to stop the store mid-run, and pay for it **24 times** in the
negative control alone — for a fact that is the same every time. The sample would also stop being the
smallest thing that answers its question, which is what
[sample-service](../services/sample-service.md) says every line of it must be.

**Leaving it** keeps a double whose faithfulness nobody checked. `PooledStoreCheckTest` reproduces
*the documented failure*; if sqlx4k does not actually behave that way, the test passes and the rule it
defends is wrong. A double is a model, and an unvalidated model is an assumption wearing a test's
clothes.

### What this does and does not settle

The scenario stays automated **against a double** — that does not change, and its `Automated:` line
keeps saying so. What changes is that the double stops being a guess: the driver check is the evidence
that the model is faithful. If the driver turns out not to behave as §1.9 consequence 1 says, then the
double is wrong, rule 6's justification moves, and finding that out is worth more than the check
passing.

It lives in `samples/oracle` — already JVM-only and already driving containers — and is **invoked by
name**, never in `make build` and never in the oracle run. [B-47](B-47-driver-behaviour-check.md)
is the implementation.

- AC: a decision recorded here. If taken: the sample holds a real pool, the oracle can stop it, and
  the scenario's `**Automated:**` line stops saying "against a double".
- Anchors: `samples/service/`, `samples/oracle/`, `docs/features/feature-health-probes.md`
