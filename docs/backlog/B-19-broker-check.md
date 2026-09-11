---
id: B-19
title: "The broker check asks for metadata, not for a socket"
status: open
priority: P2
size: S
stage: m3-probes
epic: feature-health-probes
blocked_by: [B-16]
---

# B-19 — The broker check asks for metadata, not for a socket

A readiness check for a message broker.

- **The decision and its reason.** "The socket is open" is not the question. The neighbouring finding
  in research §1.8 is a client whose broker pod was replaced: every object involved still looked
  alive, the client dialled nothing again, and every poll answered `EOFException` at five a second for
  as long as anyone watched. A metadata call on a topic the service actually uses is the cheapest
  thing that would have failed.
- **Rejected:** a TCP connect check. It is what the portfolio's chart does for the broker today, and
  it answers a question about the kernel rather than about the broker.
- Does **not** cover: reconnection. Whether a client recovers is the client's business; kore reports.

- AC: the check fails within its timeout when the broker is stopped, and passes when it is running.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/`, `kore-booblik/`
