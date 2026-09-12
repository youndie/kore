---
id: B-19
title: "The broker check asks for metadata, not for a socket"
status: done
priority: P2
size: S
stage: m3-probes
epic: feature-health-probes
blocked_by: [B-16]
---

# B-19 — The broker check asks for metadata, not for a socket

A readiness check for a message broker.

- **The decision and its reason.** "The socket is open" is not the question — it answers the kernel,
  not the broker. A metadata call on a topic the service actually uses is the cheapest thing that
  asks the broker.

  > **The reason this item was written with was wrong, and checking it produced research §1.15.** It
  > cited "the neighbouring finding in research §1.8" — a client whose broker pod was replaced,
  > dialling nothing again, `EOFException` at five a second for as long as anyone watched. §1.8
  > contains no such finding. The underlying behaviour is half true: neither booblik client
  > reconnects, and the `EOFException` and the five-a-second rate are both real and now have
  > addresses. But the first consumer **fixed this in its own `B-107`** — a generation-guarded
  > reconnect, tested — so the permanent wedge is not a present-tense fact about anything.
  >
  > What survives, and is the better reason: the client offers no recovery, so every service writes
  > its own; and *nothing detects it*. The first consumer's `/health` answers a static string that
  > never touches the broker. The check is for the detection half.
- **Rejected:** a TCP connect check. It is what the portfolio's chart does for the broker today, and
  it answers a question about the kernel rather than about the broker.
- Does **not** cover: reconnection. Whether a client recovers is the client's business; kore reports.

- AC: the check fails within its timeout when the broker is stopped, and passes when it is running.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/BrokerCheck.kt`

## Findings

* **The item's own justification cited a section that did not contain it**, and checking it produced
  [research §1.15](../research/research-architecture.md). The design survived; the reason did not.
  Half the claim is verified with addresses — neither booblik client reconnects, the peer close is
  `EOFException("broker closed the connection")`, the five-a-second rate is the consumer's own 200 ms
  poll delay. The other half was out of date: the first consumer fixed the wedge in its own `B-107`.
* **What the check is actually for is the detection half.** Nothing in the first consumer reports
  broker health — `/health` answers a static string that never touches it — and a connect-shaped
  check would not have helped, because a socket answers the kernel.
* **`kore-booblik/` is not an anchor of this item.** It was listed as one, and it should not have
  been: this item ships a *shape* that takes no client dependency, the same way `storeCheck` does. A
  booblik adapter is [B-36](B-36-booblik-adapter-targets.md), which is a question.
* **A neighbouring finding was re-checked and had grown**, recorded on
  [B-31](B-31-first-consumer-findings.md): the unflushed producer close survived konekt's rewrite and
  is now reached from `reconnect` too, so it drops records once per reconnect as well as once per
  deployment.
