---
id: B-37
title: "Decide how a public kore resolves the three agents"
status: done
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

## The decision, 2026-09-12

**The portfolio's own repository.** The second choice: `kore-observability` is portfolio-only, the
repository is declared in kore's build, and that is said where a stranger will meet it rather than
left to a resolution failure. Publishing three other projects to Maven Central for kore's sake was
not chosen; neither was inverting the dependency, which would have given up the "one line" the brief
asks for.

`https://reposilite.kotlin.website/snapshots`, declared in `settings.gradle.kts` **with a group
filter** — the same declaration and the same filter the first consumer already carries. The filter is
failure isolation, not speed: an unfiltered repository takes part in resolving every dependency, so
the day the host is unreachable Gradle disables it and fails artefacts it never served, naming the
victim instead of the cause.

**The cost was one module, and is now two** — amended 2026-09-12 while doing
[B-15](B-15-booblik-adapter.md). `kore-core`, `kore-ktor` and the Gradle plugin resolve from Maven
Central alone. `kore-observability` needs the portfolio's repository for the three agents, and
`kore-booblik` needs it for a different reason worth stating: booblik *is* on Central, at 0.3.3 —
but that version still carries the pre-migration `ru.workinprogress` package, while every consumer
resolves 0.3.4 with `io.github.youndie`. An adapter compiled against Central's 0.3.3 would reference
classes the version a real consumer resolves does not have, which fails at runtime rather than at
resolution. So the honest pin is 0.3.4, and the module joins the portfolio-only half.

Someone outside the portfolio gets the ordered shutdown, the probes, the configuration schema and
`/version`, and cannot build the two modules that adapt to software they do not run either.

### Verified before it was written down

The agents are KMP, but *being* KMP is not the same as publishing the targets kore declares — and an
artefact that published only a JVM variant would satisfy a common source set at resolution time and
fail exactly the platform kore exists for. So the coordinates were resolved rather than assumed:

| | jvm | linuxX64 | linuxArm64 | macosArm64 |
|---|---|---|---|---|
| `io.github.youndie.tracy:agent:0.2.15` | ✓ | ✓ | ✓ | ✓ |
| `io.github.youndie.metrik:agent:0.2.18` | ✓ | ✓ | ✓ | ✓ |
| `io.github.youndie.katcher:client:0.7.44` | ✓ | ✓ | ✓ | ✓ |
| `io.github.youndie.katcher:client:0.7.47` — the version kore resolves today, re-checked 2026-09-12 | ✓ | ✓ | ✓ | ✓ |

Each with its transitive `shared` module, read out of `:kore-observability:dependencies` rather than
inferred from a green compile of a module that references none of them yet.

**The versions are the first consumer's.** kore is adopted *by* that service ([B-33](B-33-publish-and-adopt.md)),
and a library that moves its consumer's agent versions as a side effect of being adopted costs more
than it says. katcher is two version lines that move independently; this names the client's.

- AC: a decision recorded here; research §1.12 amended; `docs/services/kore-library.md` §4 and §5
  updated; [B-28](B-28-observability-wiring.md) unblocked.
- Anchors: `kore-observability/build.gradle.kts`, `settings.gradle.kts`,
  `docs/research/research-architecture.md`
