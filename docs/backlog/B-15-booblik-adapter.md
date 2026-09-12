---
id: B-15
title: "The booblik participant: flush, then close"
status: open
priority: P1
size: S
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-11]
---

# B-15 — The booblik participant: flush, then close

A consumer participant for booblik.

> **Rewritten twice.** It used to say "in a `jvm()`-only module", on research D5, whose premise turned
> out to be false — `booblik-native` 0.3.3 is published for `linuxX64` and `macosArm64`. The target
> set became [B-36](B-36-booblik-adapter-targets.md), which was **answered on 2026-09-12: two
> adapters, one per client**. This item is no longer blocked on it, and the *behaviour* below has not
> changed through either rewrite — it is the reason the item exists.

## The shape, now that B-36 has answered

`kore-booblik` is **multiplatform with a per-platform actual**, and the common surface is the
`ShutdownParticipant` contract `kore-core` already owns. Nothing about booblik reaches common code:
the expect declaration is kore's own, and each actual drives one client.

| | client | package |
|---|---|---|
| `jvmMain` | `booblik-client` | `io.github.youndie.booblik.net.client` |
| `nativeMain` | `booblik-native` | `io.github.youndie.booblik.native` |

**Both actuals flush explicitly and then close**, and neither relies on `close()` doing the right
thing — because the two disagree about exactly that, and the JVM one is the one that loses records.
That makes the adapter correct on both platforms today and independent of how
[youndie/booblik#68](https://github.com/youndie/booblik/issues/68) is resolved upstream.

The module is **not in the build yet**; adding it is this item.

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

- AC: the "a JVM producer's accumulated records are flushed before it is closed" scenario of
  [feature-ordered-shutdown](../features/feature-ordered-shutdown.md) §5 holds — it is the one
  scenario in that document still marked *target*, and this item is what makes it true. Both actuals
  compile and are exercised; the JVM one is checked against the client that discards on close, since
  that is the case the scenario names.
- Anchors: `kore-booblik/src/commonMain/kotlin/io/github/youndie/kore/booblik/`,
  `kore-booblik/src/jvmMain/`, `kore-booblik/src/nativeMain/`
