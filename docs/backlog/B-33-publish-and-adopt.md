---
id: B-33
title: "Publish kore and adopt it in the first consumer"
status: open
priority: P1
size: M
stage: m6-release
blocked_by: [B-13, B-17, B-23, B-28]
---

# B-33 — Publish kore and adopt it in the first consumer

Publish `io.github.youndie:kore-*` and replace konekt's hand-written lifecycle with it.

- **The decision and its reason.** A library with one sample has been tested against the API it was
  written against. konekt is the disagreement: a real service, on the JVM, with a broker, a pool,
  three agents and a chart — and with three of the defects kore exists to prevent
  ([B-31](B-31-first-consumer-findings.md)).
- **Rejected:** adopting before the oracle is green on both platforms. A first consumer that finds the
  ordering wrong finds it in production.
- Does **not** cover: a native consumer. konekt is a JVM service, and kore's harder platform is
  therefore still covered only by the sample. That is a stated gap, not an oversight, and the next
  consumer should be one of the native services.

- AC: konekt's `ApplicationStopping` subscriber, `KonektConfig.fromEnv()` and single `/health` are
  replaced by kore; its chart carries the three probes; `/version` answers; the three numbers of
  [B-34](B-34-the-three-numbers.md) are taken before and after.
- Anchors: `konekt/server/src/main/kotlin/io/konekt/`, `konekt/charts/konekt/`
