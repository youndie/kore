---
id: B-43
title: "Make kore publishable: coordinates, version, POM"
status: done
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

## What was decided

**Coordinates:** `io.github.youndie:kore-core`, `-ktor`, `-observability`, each with the four targets
of research D1 plus the metadata module — 15 artefacts, confirmed in `~/.m2` rather than assumed.
`samples/*` publishes nothing: a sample is an experiment, and an artefact nothing should resolve is
one somebody eventually will.

**Version:** `-PVERSION`, the portfolio's existing scheme, defaulting to `0.1.0-SNAPSHOT`. A build
that forgets the property should produce something obviously unreleased rather than a number that
looks like a release.

**The JVM floor stays at 25**, and the argument is reversibility rather than preference. Lowering it
later breaks nobody — a consumer on 25 can use a library compiled for 17 — while raising it breaks
everyone below. Nothing in kore *needs* 25; it is a choice, so it is written where the choice is.

**kore configures `maven-publish` itself and does not use the portfolio's `sborka.publish`
convention plugin.** That plugin is fetched in `pluginManagement`, which Gradle evaluates before any
settings plugin runs — so a build using it could not be *configured* at all without the portfolio's
repository reachable. [B-37](B-37-agents-not-on-central.md) accepted that cost for one module's
dependencies; paying it for the whole build would make kore unbuildable for the outsiders it is
published for. This is the one place kore deliberately diverges from the house style, and the reason
is the property B-37 was careful to protect.

## Verified through the real path

`publishToMavenLocal`, then a **scratch consumer** in `/tmp` that resolves `kore-core` and
`kore-ktor` from `mavenLocal()` and *compiles against the API* — `ReadinessGate`, `shutdownSequence`,
`ConfigKey`, `KoreRoutes` — on `jvm` and `linuxX64`. Both compile tasks executed and produced
`ConsumerKt.class` and a `linuxX64` klib.

Compiling against it rather than only resolving it is the point: a module that resolves and is never
compiled against is how a missing metadata variant goes unnoticed until the first native consumer.

**With a control.** Asking for `kore-core:9.9.9-NOPE` fails with *"Could not find"*, and the real
version then resolves — so the green run is resolution rather than something already on a classpath.
