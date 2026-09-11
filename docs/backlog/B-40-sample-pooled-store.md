---
id: B-40
title: "Give the sample a real pooled store the oracle can stop underneath it"
status: question
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

- AC: a decision recorded here. If taken: the sample holds a real pool, the oracle can stop it, and
  the scenario's `**Automated:**` line stops saying "against a double".
- Anchors: `samples/service/`, `samples/oracle/`, `docs/features/feature-health-probes.md`
