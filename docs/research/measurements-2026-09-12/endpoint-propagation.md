# Endpoint propagation after readiness falls — [B-20](../../backlog/B-20-pre-drain-default.md), 2026-09-12

How long after a pod's readiness goes false does a request still reach it through its `Service`?
That quantity is what [feature-health-probes](../../features/feature-health-probes.md) §4's
**five-second pre-drain wait** is an estimate of, and until now it was an estimate.

## What was measured, and on what

| | |
|---|---|
| Cluster | a **single-node** k0s `v1.36.3+k0s`, Hetzner, in a namespace created for this and deleted after |
| Subject | stock `nginx:alpine`, readiness `periodSeconds: 1`, `failureThreshold: 1` |
| Traffic | one pod curling **the Service** every 20 ms — not the pod IP, since the quantity is what kube-proxy does with the endpoint |
| Trigger | removing the file the readiness probe reads, so readiness falls on the kubelet's **next** probe |
| Repetitions | 5 flips, 4 of them usable |
| Raw | [`raw-endpoint-propagation.log`](raw-endpoint-propagation.log) |

**kore is not in this measurement, and does not need to be.** The quantity belongs to the cluster:
any pod behind a `Service` whose readiness can be flipped measures the same thing. Using a stock
image also removed the need to publish kore's sample to a registry the cluster could pull from.

**Both timestamps come from one clock.** The kubelet's probe and the client's traffic land in the
same nginx access log, with `$msec` for millisecond resolution — so the answer carries no skew
between two pods' clocks. `t0` is the first `/ready` that answered `404`, which is the moment the
kubelet learned; `t1` is the last `/traffic` the pod served.

## The numbers

| run | readiness fell | last request served | propagation |
|---|---|---|---|
| 1 | `…725.142` | `…725.186` | **44 ms** |
| 2 | `…744.145` | `…744.220` | **75 ms** |
| 3 | `…783.154` | `…783.200` | **46 ms** |
| 4 | `…803.158` | `…803.237` | **79 ms** |

**Median 61 ms** (44–79 ms), n = 4.

## What this settles, and what it does not

**It is a floor, not the answer.** One node means one kube-proxy, one kubelet, no watch fan-out and
an unloaded API server — the optimistic end of every factor. This is the same objection that made a
`kind` measurement not worth taking, and it applies here too; what a real cluster adds is a *bigger*
number, never a smaller one.

So the measurement can **refute** and cannot **confirm**: had propagation here exceeded five seconds,
the default would be definitively wrong. It is 61 ms — roughly **eighty times** under the default.

**What it does say, and it is worth saying:** the quantity is milliseconds on a small cluster, not
seconds. The five-second default is conservative by a large factor, and that conservatism is not
free — it is five seconds added to *every* pod's shutdown, on every rollout.

**Why it stays at five seconds anyway.** Lowering it would trade a measured margin on a small cluster
for an unmeasured one on a large cluster, which is the wrong direction for a default nobody reads
twice (Risk 4). What would justify a change is the same measurement on a **multi-node cluster under
load** — and that is the one thing this run cannot stand in for.
