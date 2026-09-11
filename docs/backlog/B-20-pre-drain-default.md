---
id: B-20
title: "Measure and settle the pre-drain default"
status: open
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
