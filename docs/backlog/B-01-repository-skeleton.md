---
id: B-01
title: "Repository skeleton and the multiplatform build"
status: done
priority: P0
size: M
stage: m0-shape
---

# B-01 — Repository skeleton and the multiplatform build

There is no code. The first thing kore needs is a build that produces the five modules of
[kore-library](../services/kore-library.md) §2a on the target set decided in
[research-architecture](../research/research-architecture.md) D1 — `jvm`, `linuxX64`, `linuxArm64`,
`macosArm64`, with `kore-booblik` on `jvm` alone.

- **The decision and its reason.** Targets are named explicitly rather than chosen from `os.name`.
  A build that picks its native target from the build machine publishes exactly one native variant
  — whichever suited the machine — and a consumer on the other architecture has nothing to resolve
  against. This is a mistake the portfolio has already made and fixed once.
- **Rejected:** starting JVM-only and adding native later. Every fact that makes kore necessary
  (research §1.1, §1.3, §1.5) is invisible from the JVM, so a JVM-first library would be designed
  against the easy platform and ported into the hard one.
- Does **not** cover: publishing (B-33) or the Gradle plugin (B-26).

- AC: `./gradlew build` produces klibs for the three native targets and a jar for the JVM, on a
  machine that has none of them cached; the module graph matches
  [kore-library](../services/kore-library.md) §2a; every version in the catalogue carries a comment
  saying why it is that version, read from the registry rather than recalled.
- Anchors: `settings.gradle.kts`, `gradle/libs.versions.toml`, `kore-core/build.gradle.kts`

## Iteration 1 — 2026-09-11

**Done, with the module list amended.** Three modules, not four: `kore-core`, `kore-ktor`,
`kore-observability`. The amendment is the finding below, and it is recorded here rather than applied
silently to the acceptance criterion above.

Verified on the Linux box (`wsl-run ./gradlew build`), by artefact and by result file rather than by
reading `BUILD SUCCESSFUL` through a pipe:

| Claim | How it was checked |
|---|---|
| klibs for `linuxX64`, `linuxArm64`, `macosArm64` and a jar for the JVM | the four output paths listed with sizes; `klib dump-metadata` on the `linuxX64` and `macosArm64` klibs shows `korePlatform` in both |
| the Apple klib cross-compiles on a Linux host | `compileTestKotlinMacosArm64` ran there; `linkDebugTestMacosArm64` and `macosArm64Test` were **SKIPPED**, which is the honest half |
| the tests actually ran | three result XMLs written, `tests="1" failures="0"` each, for `jvmTest` and `linuxX64Test`. `linuxArm64Test` and `macosArm64Test` wrote none — a host cannot run what it cannot execute |
| the new test is worth its line | mutation: flipping `canEnumerateEnvironment` to `false` in the Linux actual turned `linuxX64Test` red (1 of 2 failed); reverted, tree clean |
| `ktor-server-core` 3.5.2 resolves on all four targets | its metadata klibs were transformed for `nativeMain` in `kore-ktor` |

**Three findings, all from checking rather than reading, each now an item:**

1. **[B-36](B-36-booblik-adapter-targets.md)** — `booblik-native` 0.3.3 is on Maven Central for
   `linuxX64` and `macosArm64`. Research §1.7 said there was no such thing and D5 rested on it. So
   `kore-booblik` is **not in this change**: a module built to a superseded decision is worse than a
   module that is not there yet. §1.7 and D5 corrected at the point of divergence.
2. **[B-37](B-37-agents-not-on-central.md)** — none of tracy, metrik or katcher is on Maven Central.
   `kore-observability` therefore ships with no agent dependencies; a public library that declares a
   repository outsiders cannot reach fails to resolve for them as a missing version, which reads as a
   broken release. New research §1.12.
3. **[B-38](B-38-native-dispatcher.md)** — `Dispatchers.IO` is `internal` on Kotlin/Native, recorded
   in booblik's native module from a compile rather than from the documentation. kore's health loop
   and its parked coroutine both need an answer to that. Noted in §1.8.

**What was deliberately not done:** the Gradle plugin (B-26), the samples (B-05), publishing (B-33),
and any CI job that builds — the cadence of that is B-07, which has to measure a native link first.
No style or lint tooling was added: whether kore uses the portfolio's shared Gradle conventions is a
question nothing in the documents answers, and inventing an answer inside a skeleton item would have
been the wrong place to decide it.
