---
id: B-36
title: "Decide kore-booblik's target set — D5's premise is gone"
status: question
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

**Until it is answered, `kore-booblik` is not in the build at all.** A module written to a decision
known to be superseded is worse than a module that is not there yet.

- AC: a decision recorded here; research D5 amended to match; `docs/services/kore-library.md` §2a
  updated; [B-15](B-15-booblik-adapter.md) rewritten against the answer.
- Anchors: `docs/research/research-architecture.md`, `docs/services/kore-library.md`,
  `settings.gradle.kts`
