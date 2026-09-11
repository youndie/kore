---
id: B-15
title: "The booblik participant: flush, then close (JVM only)"
status: open
priority: P1
size: S
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-11]
---

# B-15 — The booblik participant: flush, then close (JVM only)

A consumer participant for booblik, in a `jvm()`-only module.

- **The decision and its reason.** Research §1.8: `Producer.close()` is `mailbox.close()`, and the
  loop's `finally` completes every queued record **exceptionally** rather than sending it. So "close
  the consumers" is two verbs, and the first one is the one everybody omits — including the
  portfolio's most complete service today. The flush is bounded, because a flush against a dead broker
  would otherwise eat the release budget.
- **Rejected:** an optional dependency resolved by reflection, so that the adapter could live in
  common code. Reflection is not available on Kotlin/Native, and an API whose shape differs per
  platform is one whose documentation is wrong on one of them (research D5).
- Does **not** cover: a native booblik client. booblik's client is Kotlin/JVM by its own recorded
  decision; changing that is booblik's call, not kore's.

- AC: the "a producer's accumulated records are flushed before it is closed" scenario of
  feature-ordered-shutdown §5 holds against a real broker.
- Anchors: `kore-booblik/src/main/kotlin/io/github/youndie/kore/booblik/`
