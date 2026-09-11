---
id: B-31
title: "Findings against the first consumer, found by reading"
status: open
priority: P2
size: S
stage: m6-release
---

# B-31 — Findings against the first consumer, found by reading

Three defects in [konekt](https://github.com/youndie/konekt), found while verifying research §1.6,
§1.8 and §1.11. None was found by an incident; all three are live.

1. **tracy's last flush is unreachable.** `Observability.kt:73` constructs `TracyDelivery`, calls
   `start(this)` and discards the reference, so `stop` — which exists precisely to make a final
   bounded flush — can never be called. Every shutdown loses the last flush interval of records,
   including the records explaining the shutdown.
2. **The broker producer is closed without being flushed.** `BrokerConnection.close()` calls
   `producer.close()`, and booblik's `close` completes queued records exceptionally rather than
   sending them. Up to a linger window of published events is dropped per deployment, invisibly.
3. **All three probes point at one route that reads nothing.** `charts/konekt/templates/server.yaml`
   sends `startupProbe`, `livenessProbe` and `readinessProbe` to `/health`, which is
   `call.respondText("ok")`. A pod whose database is unreachable reports itself ready.

- **The decision and its reason.** These are reported to konekt, not fixed from here, and they are
  kept in kore's backlog because they are the evidence that the library is worth building: nothing is
  wrong with any library involved, and the wiring between them was written once and never revisited.
- Does **not** cover: fixing konekt. Adoption is [B-33](B-33-publish-and-adopt.md), and (1) and (3)
  disappear with it.

- AC: the three are reported in konekt's own backlog with the paths above; this item records where.
- Anchors: `konekt/server/src/main/kotlin/io/konekt/observability/Observability.kt`,
  `konekt/server/src/main/kotlin/io/konekt/events/BrokerConnection.kt`,
  `konekt/charts/konekt/templates/server.yaml`
