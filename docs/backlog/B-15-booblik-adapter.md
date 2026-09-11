---
id: B-15
title: "The booblik participant: flush, then close"
status: open
priority: P1
size: S
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-11, B-36]
---

# B-15 — The booblik participant: flush, then close

A consumer participant for booblik.

> **Rewritten during [B-01](B-01-repository-skeleton.md).** This item used to say "in a `jvm()`-only
> module", on research D5, whose premise turned out to be false — `booblik-native` 0.3.3 is published
> for `linuxX64` and `macosArm64`. Which targets the adapter has is now
> [B-36](B-36-booblik-adapter-targets.md), and this item is blocked on it. The *behaviour* below is
> unchanged and is the reason the item exists.

- **The decision and its reason.** Research §1.8: `Producer.close()` is `mailbox.close()`, and the
  loop's `finally` completes every queued record **exceptionally** rather than sending it. So "close
  the consumers" is two verbs, and the first one is the one everybody omits — including the
  portfolio's most complete service today. The flush is bounded, because a flush against a dead broker
  would otherwise eat the release budget.
- **Rejected:** an optional dependency resolved by reflection, so that the adapter could live in
  common code. Reflection is not available on Kotlin/Native, and an API whose shape differs per
  platform is one whose documentation is wrong on one of them (research D5, the half that stands).
- **And the behaviour is needed on both clients, not one.** The two published producers disagree
  about what `close()` does — the native one sends the accumulated batch, the JVM one fails it
  (research §1.8). kore flushes explicitly on both rather than relying on either, because relying on
  the one that happens to be right today is how the difference goes unnoticed.

- AC: the "a producer's accumulated records are flushed before it is closed" scenario of
  feature-ordered-shutdown §5 holds against a real broker.
- Anchors: `kore-booblik/src/main/kotlin/io/github/youndie/kore/booblik/`
