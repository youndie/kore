---
id: B-26
title: "The Gradle plugin that generates the build identity"
status: done
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
- Anchors: `kore-build/src/main/kotlin/io/github/youndie/kore/gradle/`, `samples/service/build.gradle.kts`

## What it cost to make the AC true

* **The sample applying the plugin proved nothing on its own.** A generated file nothing references
  compiles as an unused object, so a broken source-directory wiring would have produced a green build
  and an absent value. `samples/.../Announce.kt` names `KoreBuildIdentity` in one line, which turns
  that into a compile error; unwiring the plugin deliberately was checked to break both targets.
* **The "input, not a side effect" claim was true, and the sentence next to it was not.** The
  plugin's comment said an unchanged identity left the compilation UP-TO-DATE. The end-to-end test
  written to confirm it refuted it: `builtAt` was a wall clock, so every build rewrote a source of
  `commonMain` and recompiled everything under it. The date is now kept when nothing else changed —
  see the quirk in [feature-build-identity](../features/feature-build-identity.md) §6.
* **A surviving mutation found an empty commit.** Returning the empty output instead of `null` on a
  git that never answers, or is not installed, survived the first test set: both would have compiled
  an empty string into a binary rather than `unknown`. The executable and the timeout are parameters
  because neither branch can be provoked against the real `git` on the PATH.
