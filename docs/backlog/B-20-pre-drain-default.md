---
id: B-20
title: "Measure and settle the pre-drain default"
status: question
priority: P1
size: S
stage: m3-probes
epic: feature-health-probes
blocked_by: [B-09, B-33]
---

# B-20 — Measure and settle the pre-drain default

> **`blocked_by` corrected on 2026-09-12 to include [B-33](B-33-publish-and-adopt.md).** This item's
> acceptance is a figure measured **in a real cluster** — how long after readiness goes false a pod
> still receives a request — and that quantity is endpoint propagation through kube-proxy. There is no
> local substitute: a container on a laptop has no Service in front of it. kore is deployed nowhere,
> so the measurement cannot be taken until something deploys it. The loop picked this item, found it
> unmeasurable, and fixed the dependency rather than producing a number from somewhere else.

## Still unmeasurable after B-33, and the blocker was named wrongly — 2026-09-12

B-33 is done: kore `0.1.0` is published and adoption is **proposed** as
[konekt#35](https://github.com/youndie/konekt/issues/35). But this item never needed a *published*
kore — it needs a kore **running behind a Service**, and nothing runs one yet. The `blocked_by` named
the wrong precondition, which is why the loop picked this item twice and found it unmeasurable twice.

**What was considered and rejected, so the next reader does not redo it:**

* **A local single-node cluster** (`kind` on the build box) would give a real kube-proxy, a real
  Service and real Endpoints — everything a container on a laptop lacks. It would also give a
  **systematically optimistic** number: one node, an unloaded API server, no watch fan-out. A default
  derived from it would be smaller than reality needs, which is the dangerous direction for exactly
  the reason Risk 4 gives — a default that is wrong is worse than none, because nobody reads it
  again. It can only *refute*: if propagation there exceeded five seconds, five seconds would be
  definitively wrong. It cannot confirm.
* **The portfolio's cluster.** Access exists, and it is shared and production-adjacent. Putting a new
  workload there to take a measurement is a change to somebody else's infrastructure, and it is not
  this backlog's to make.

**The question, then:** deploy the sample to a test namespace to take the figure, or wait until
something adopts kore and measure it where it lands? The second costs nothing and has no date; the
first needs a yes, and would answer this in an afternoon.

Until then §4's five seconds keeps its marker. It is a hypothesis with an address, which is the
honest state — not a number pretending to be measured.

[feature-health-probes](../features/feature-health-probes.md) §4 ships a five-second pre-drain wait
and says in the same table that it is **a hypothesis**: an estimate of rule propagation, not a
measurement.

- **The decision and its reason.** A library must ship a default, and a default that is wrong is worse
  than none because nobody reads it again (Risk 4). So the number ships with a marker and an address,
  and this item is the address.
- **Rejected:** shipping no default and requiring every service to choose. That moves the same guess
  to somebody with less information about it.
- Does **not** cover: multi-replica rollouts. What is measured here is one pod, and whether a rollout
  drops a request depends on surge settings kore does not control.

- AC: a measured figure for how long after readiness goes false a pod still receives a request, taken
  in a real cluster; the default is confirmed or changed; if it changes, research §1.10 is amended at
  the point of divergence and feature-health-probes §4 follows it.
- Anchors: `docs/features/feature-health-probes.md`, `docs/research/research-architecture.md`
