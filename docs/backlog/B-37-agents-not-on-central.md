---
id: B-37
title: "Decide how a public kore resolves the three agents"
status: wip
priority: P1
size: S
stage: m5-wiring
---

# B-37 — Decide how a public kore resolves the three agents

Found during [B-01](B-01-repository-skeleton.md), by trying to resolve them rather than by reading a
build file. `io.github.youndie:tracy-agent`, `metrik-agent` and `katcher-client` all answer **404**
on Maven Central. They are published to the portfolio's own repository, which the first consumer
declares with a group filter in its `settings.gradle.kts`.

kore is public and meant to be consumed. A module that declares a repository outsiders cannot reach
fails to resolve for them — not with a clear message, but as a missing version, which reads as a
broken release.

**The choices, and all three are the owner's:**

- **publish the three agents to Maven Central**, as kompot, petich, viddik, chronik, bochka and
  booblik already are. Most work, and it is work in three other repositories;
- **accept that `kore-observability` is portfolio-only**, declare the repository in kore's build, and
  say so in the README and in `docs/services/kore-library.md` §5. Cheapest, and it makes one of
  kore's five features unavailable to anyone outside;
- **invert the dependency**: kore declares a small interface and the agents are wired by the consumer,
  so kore depends on none of them. Closest to what kore already does for booblik participants
  (D5's surviving half), and it gives up the "one line" the brief asks for.

**Until it is answered, `kore-observability` has no agent dependencies.** The module exists and
builds; it just does nothing yet.

- AC: a decision recorded here; research §1.12 amended; `docs/services/kore-library.md` §4 and §5
  updated; [B-28](B-28-observability-wiring.md) unblocked.
- Anchors: `kore-observability/build.gradle.kts`, `settings.gradle.kts`,
  `docs/research/research-architecture.md`
