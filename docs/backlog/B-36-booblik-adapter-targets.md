---
id: B-36
title: "Decide kore-booblik's target set — D5's premise is gone"
status: done
priority: P1
size: S
stage: m2-shutdown
---

# B-36 — Decide kore-booblik's target set — D5's premise is gone

Found during [B-01](B-01-repository-skeleton.md). `docs/services/kore-library.md` §2a names
`kore-booblik` as a `jvm()`-only module, on decision D5, whose premise was research §1.7: booblik's
client is Kotlin/JVM. **That premise is false.** `io.github.youndie.booblik:booblik-native` 0.3.3 is
published on Maven Central for `linuxX64` and `macosArm64`, with `Connection`, `Consumer`, `Producer`
and `Socket` — a multiplatform split of the protocol, not a reimplementation.

What made the research wrong is worth stating, because it is not a typo: booblik's own research still
records *"Р8. Клиент остаётся Kotlin/JVM"*, accurate about the day it was written and superseded by a
later milestone that did not amend it. A recorded decision is a fact about the past.

**Why this is a question and not a decision I take.** There are two clients with two package names
and two APIs (`io.github.youndie.booblik.net.client`, `io.github.youndie.booblik.native`), so there
is no single common adapter either way. The choice has a price:

- **one JVM adapter now** — smallest, matches the only consumer that exists (konekt is a JVM
  service), and leaves the native-first claim untested on the one stage that most needs it;
- **two adapters over the two clients** — `kore-booblik` becomes KMP with a per-platform actual.
  Twice the code and twice the maintenance for a stage no native service uses yet;
- **neither, until a native service actually publishes to the broker** — costs nothing and admits
  that the booblik stage is currently a JVM story.

## The decision, 2026-09-12: two adapters, one per client

**`kore-booblik` becomes multiplatform with a per-platform actual** — the second option. The JVM
actual drives `io.github.youndie.booblik.net.client`, the native one drives
`io.github.youndie.booblik.native`, and the common surface is the `ShutdownParticipant` contract kore
already owns: *flush with a deadline, then close*, in that order.

**What it costs**, said plainly because the item asked for it: twice the code and twice the
maintenance for a stage no native service uses yet, and every booblik release has to be checked
against two APIs rather than one.

**What it buys, and why that is the half that decides it.** kore's whole claim is that the ordering
is a specification rather than a side effect — and the one ordering defect this repository *measured*
is native-only: `ApplicationStopping` breaking 48 requests on Kotlin/Native and none on the JVM
(`measurements-2026-09-11/negative-control.md`). A JVM-only adapter would leave the stage that
matters most untested on the platform where this library's premise was demonstrated. The cheaper
option is cheaper exactly where kore cannot afford it.

**And the two clients disagree about the thing the adapter exists for.** The JVM `close()` discards
the accumulated batch; the native one sends it first (research §1.8, filed as
[youndie/booblik#68](https://github.com/youndie/booblik/issues/68)). A single adapter written against
one of them would be right on one platform by accident. Two actuals make the difference explicit,
and the flush-then-close contract makes kore independent of whichever way the disagreement is
resolved upstream.

**Until this is implemented, `kore-booblik` is still not in the build.** The decision is recorded;
the module arrives with [B-15](B-15-booblik-adapter.md).

- AC: a decision recorded here; research D5 amended to match; `docs/services/kore-library.md` §2a
  updated; [B-15](B-15-booblik-adapter.md) rewritten against the answer.
- Anchors: `docs/research/research-architecture.md`, `docs/services/kore-library.md`,
  `settings.gradle.kts`
