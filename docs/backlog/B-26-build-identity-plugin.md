---
id: B-26
title: "The Gradle plugin that generates the build identity"
status: wip
priority: P1
size: M
stage: m5-wiring
epic: feature-build-identity
blocked_by: [B-01]
---

# B-26 — The Gradle plugin that generates the build identity

A plugin that emits a Kotlin source file carrying the commit, the dirty flag, the build timestamp and
the version.

- **The decision and its reason.** Research D7: Kotlin/Native has no JVM-style resource loading and no
  manifest, so a generated source file is the one mechanism identical on both platforms. A
  resource-based implementation works on the JVM, compiles on native, and returns nothing there —
  which is the shape of bug this repository is written to avoid.
- **The generated file is an input of the compilation, not a side effect of the build.** The failure
  mode of getting that wrong is a stale commit hash and a green build: a value that looks
  authoritative and is a release behind. That is why the "changing the commit changes the compiled
  value, without cleaning" scenario exists in
  [feature-build-identity](../features/feature-build-identity.md) §4.
- A build with no `.git` degrades to `unknown` rather than failing — a library that cannot be built in
  a container without `.git` cannot be built in half the CI systems there are. `unknown` reads as an
  absence; an invented-looking hash does not.

- AC: the four scenarios of feature-build-identity §4 that concern the build hold; the sample applies
  the plugin on both targets.
- Anchors: `kore-build/src/main/kotlin/io/github/youndie/kore/build/`, `samples/service/build.gradle.kts`
