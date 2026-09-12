---
id: B-20
title: "Measure and settle the pre-drain default"
status: done
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

## Measured 2026-09-12 — and the default stays

The owner said to deploy into a test namespace. Done, on a single-node k0s cluster, in a namespace
created for it and **deleted afterwards** — full report in
[measurements-2026-09-12/endpoint-propagation.md](../research/measurements-2026-09-12/endpoint-propagation.md).

**Median 61 ms** (44–79 ms, n = 4) from readiness falling to the last request the pod still served
through its `Service`. Against a five-second default: **about eighty times under it**.

**kore is not in the measurement, and does not need to be.** The quantity belongs to the cluster — any
pod behind a `Service` whose readiness can be flipped measures the same thing. A stock `nginx:alpine`
also removed the registry problem: kore's sample image lives on a build box and the cluster has no way
to pull it, and the portfolio's registry credentials are not this backlog's to handle.

**Both timestamps come from one clock** — the kubelet's probe and the client's traffic land in the
same access log, so the answer carries no skew between two pods' clocks.

### What it settles, and what it does not

**It is a floor.** One node means one kube-proxy, one kubelet, no watch fan-out, an unloaded API
server — the optimistic end of every factor, which is the same objection that made a `kind`
measurement not worth taking. A real cluster adds to this number, never subtracts.

So it can **refute** and cannot **confirm**: propagation above five seconds here would have settled
that the default is wrong. It is 61 ms.

**The default stays**, and now for a stated reason rather than an estimate: lowering it would trade a
measured margin on a small cluster for an unmeasured one on a large cluster, which is the wrong
direction for a number nobody reads twice (Risk 4). What would justify lowering it is the same run on
a **multi-node cluster under load**.

**And the cost is now named too.** Five seconds is added to every pod's shutdown on every rollout. The
conservatism is deliberate, not free.

## How it stood before the measurement — 2026-09-12

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
