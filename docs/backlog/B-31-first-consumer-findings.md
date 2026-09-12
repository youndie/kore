---
id: B-31
title: "Findings against the first consumer, found by reading"
status: done
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

## All three re-verified 2026-09-12 against konekt `1b67eec`

Re-read rather than trusted, because finding (2) had already changed shape once. All three are live;
(2) is worse than this item said.

| # | Verdict | Current address |
|---|---|---|
| 1 | **live, verbatim** — `TracyDelivery(agent, agentConfig).start(this)`, the reference discarded | `server/src/main/kotlin/io/konekt/observability/Observability.kt:73` |
| 2 | **live and wider** — see below | `server/src/main/kotlin/io/konekt/events/BrokerConnection.kt:86`, `:110-111`, `:115` |
| 3 | **live, verbatim** — three probes, one route, `respondText("ok")` | `charts/konekt/templates/server.yaml:103`, `:109`, `:114`; `server/src/main/kotlin/io/konekt/Application.kt:188` |

Finding (1) is worth the check it got: `TracyDelivery.stop(grace)` does exist and is exactly the
bounded final flush the finding claims is unreachable — it cancels the loop and then runs `flushOnce`
under `withTimeoutOrNull` (tracy `agent/src/commonMain/kotlin/io/github/youndie/tracy/agent/TracyDelivery.kt:82-87`).
The defect is not that konekt calls it wrongly; it is that nothing can call it at all.

## Re-checked 2026-09-12 while doing [B-19](B-19-broker-check.md)

Finding (2)'s address has moved and the finding has grown. `BrokerConnection` was rewritten for
konekt's own `B-107` — it now holds a generation and a `reconnect(seen)` guarded by it, with tests
(`server/src/test/kotlin/io/konekt/events/BrokerReconnectTest.kt`). The unflushed close survived the
rewrite as `closeQuietly`, which calls `producer.close()` with no `flush()` before it — and there is
no `flush` anywhere under `server/src/main/kotlin/io/konekt/events/`.

**`closeQuietly` is called from `reconnect` as well as from `close`.** So the loss is no longer once
per deployment: it is once per deployment *and once per broker reconnect*, which is exactly when the
broker was replaced and the records explaining it matter. Finding (2) is live, and its blast radius
is larger than this item said.

Findings (1) and (3) were not re-read; (3)'s route is unchanged (`Application.kt:185-189` is still
`respondText("ok")`).

- **The decision and its reason.** These are reported to konekt, not fixed from here, and they are
  kept in kore's backlog because they are the evidence that the library is worth building: nothing is
  wrong with any library involved, and the wiring between them was written once and never revisited.
- Does **not** cover: fixing konekt. Adoption is [B-33](B-33-publish-and-adopt.md), and (1) and (3)
  disappear with it.

- AC: the three are reported in konekt's own backlog with the paths above; this item records where.
  **The recording half is done. The filing half is the question below.**

## Filed 2026-09-12 — as issues, which is what was asked for

The owner chose konekt's **issue tracker** rather than its backlog, so the three went there directly
and no pull request against konekt was needed.

| # | Finding | Issue |
|---|---|---|
| 1 | the final tracy flush is unreachable | [youndie/konekt#30](https://github.com/youndie/konekt/issues/30) |
| 2 | the broker producer is closed without a flush, on every reconnect | [youndie/konekt#31](https://github.com/youndie/konekt/issues/31) |
| 3 | all three probes point at `/health`, which reads nothing | [youndie/konekt#32](https://github.com/youndie/konekt/issues/32) |

Written in English, matching konekt's own documentation, and each carrying the addresses re-verified
against `1b67eec` rather than the ones this item was written with. (1) and (3) name the kore adoption
that would remove them and say that adoption has no date, so neither reads as a reason to wait; (2)
says plainly that it does **not** disappear with adoption, because it is a booblik call konekt makes
itself.

Each also links the corresponding upstream issue where there is one — (1) to
[youndie/tracy#32](https://github.com/youndie/tracy/issues/32), (2) to
[youndie/booblik#68](https://github.com/youndie/booblik/issues/68) — so the reader can see whether
the cheaper fix is the one in somebody else's repository.

## What the question was, before it was answered

The acceptance ended in **another repository**, and the loop that produced this was pointed at kore's
backlog. Opening a pull request against a second repository was outside that, so it stopped and asked
— offering konekt's issues as an alternative destination, which is the one that was chosen.

The three drafts below are what was prepared while waiting. They are kept because the filed issues
are longer and differently shaped, and the short form is what a reader of this backlog wants.

### Ready to file — konekt B-125

> **The final tracy flush is unreachable.** `Observability.kt:73` constructs `TracyDelivery`, calls
> `start(this)` and discards the reference. `TracyDelivery.stop(grace)` — which cancels the delivery
> loop and then runs one bounded `flushOnce` — has no caller and cannot have one. Every shutdown
> therefore loses up to a flush interval of records, *including the records explaining the shutdown*,
> which are the ones worth having. The fix is to hold the instance and stop it on the way down,
> before the scope it runs in is cancelled.

### Ready to file — konekt B-126

> **The broker producer is closed without being flushed, on every reconnect as well as every
> deployment.** `BrokerConnection.closeQuietly` (`:115`) calls `producer.close()` with no preceding
> `flush()`, and there is no `flush` anywhere under `server/src/main/kotlin/io/konekt/events/`.
> booblik's JVM `close` completes queued records *exceptionally* rather than sending them — its
> native sibling sends them first, so this is the JVM client's behaviour specifically (kore research
> §1.8). `closeQuietly` is reached from `close()` (`:111`) and from `reconnect` (`:86`), so the loss
> happens once per deployment **and once per broker reconnect** — which is exactly when the broker
> was replaced and the records explaining it matter most. The fix is `flush()` with a deadline before
> `close()`.

### Ready to file — konekt B-127

> **All three probes point at one route that reads nothing.** `charts/konekt/templates/server.yaml`
> sends `startupProbe` (`:103`), `livenessProbe` (`:109`) and `readinessProbe` (`:114`) to `/health`,
> which is `call.respondText("ok")` (`Application.kt:188`). A pod whose database is unreachable
> reports itself ready and keeps taking traffic. The chart's own comment argues correctly that a
> probe must not read the store — and then points readiness at the same route. Readiness is the one
> probe that *should* answer for dependencies, because its failure is the cheap one: traffic stops,
> nothing is killed.
>
> (1) and (3) both disappear on adoption — kore [B-33](B-33-publish-and-adopt.md) — so the choice is
> whether konekt fixes them now or waits. (2) does not: it is a booblik call konekt makes itself.
- Anchors: `konekt/server/src/main/kotlin/io/konekt/observability/Observability.kt`,
  `konekt/server/src/main/kotlin/io/konekt/events/BrokerConnection.kt`,
  `konekt/charts/konekt/templates/server.yaml`
