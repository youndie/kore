---
id: B-43
title: "Make kore publishable: coordinates, version, POM"
status: open
priority: P1
size: S
stage: m6-release
blocked_by: []
---

# B-43 — Make kore publishable: coordinates, version, POM

Split out of [B-33](B-33-publish-and-adopt.md), which turned out to be three decisions wearing one
number. This is the half that needs nobody's permission: **kore declares no `group`, no `version` and
no `maven-publish` at all** today, so there is nothing to publish even once somebody says to.

- **The decision and its reason.** Publishing setup is verifiable without publishing anything:
  `publishToMavenLocal` produces the artefacts, and a consumer can resolve them from `mavenLocal()`.
  That makes the release itself a one-line decision later rather than a decision bundled with an
  hour of build work.
- **What it must get right, because these are expensive afterwards:** the coordinates
  (`io.github.youndie:kore-core` and siblings), the JVM floor — the catalogue currently pins 25 with
  a note saying the floor is a publishing decision and belongs here — and which modules are published
  at all, given that `kore-observability` resolves from the portfolio repository and the other three
  do not ([B-37](B-37-agents-not-on-central.md)).
- **Rejected:** publishing from a laptop. Whatever publishes has to be the thing that also builds all
  four targets, which is CI.
- Does **not** cover: pressing the button, or the first consumer. Those are B-33.

- AC: `./gradlew publishToMavenLocal` produces every module for every declared target; a scratch
  project resolves `kore-core` and `kore-ktor` from `mavenLocal()` and compiles against them on the
  JVM and on `linuxX64`; the POM names the licence and the project; the JVM floor is decided in
  writing.
- Anchors: `build.gradle.kts`, `gradle/libs.versions.toml`
