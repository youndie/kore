---
id: B-01
title: "Repository skeleton and the multiplatform build"
status: wip
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
