---
id: feature-build-identity
title: /version — which build this is
type: feature
status: active
owner: unassigned
involved_services:
  - kore-library
  - sample-service
client_entries: []
api:
  - endpoint-kore-admin
tags: [build, gradle, version]
---

# `/version` — which build this is

> **`status: active`** — designed, not built. The platform constraint behind the design is
> [research-architecture](../research/research-architecture.md) D7; the gap it fills is §1.11.

## 1. Overview

`GET /version` answers with the commit, the build timestamp and the version the running binary was
built from. The values are compiled in by a Gradle plugin that generates a Kotlin source file — not
read from a resource, not read from a manifest, because Kotlin/Native has neither.

The question it answers is the one asked during a deploy and during an incident: *is what is running
the thing we think we pushed?* Today the portfolio answers it by trusting that the image tag matches
the chart value that matches the commit — three claims, each true separately, and the case where they
disagree is precisely the case somebody is investigating.

## 2. Business rules

1. **The value is compiled in, not read at runtime.** A value read from a file or an environment
   variable is a value the deployment can get wrong in the same way it got the image wrong.
2. **The commit is the commit that was built**, including whether the working tree was dirty. A
   `-dirty` marker is the difference between "this is commit `abc1234`" and "this is something
   somebody had on their laptop".
3. **A build with no git available still builds.** The plugin degrades to `unknown` rather than
   failing — a library that cannot be built in a container without `.git` is a library that cannot be
   built in half the CI systems there are. `unknown` is a value that reads as an absence; an
   invented-looking hash is not.
4. **The release identifier `/version` reports is the same one the agents get.** One value, one
   place. Today the release travels as an environment variable into metrik's deploy marker and
   katcher's crash group (research §1.11), and nothing relates it to the code that was built; kore
   makes the compiled-in identity the source and lets the environment override it, so a disagreement
   between the two is visible rather than impossible to notice.
5. **The generated source is an input of the compilation, not a side effect of the build.** The
   failure mode of getting this wrong is a stale commit hash and a green build — a value that looks
   authoritative and is a release behind.
6. **`/version` can be reduced but not removed.** See the quirk in
   [endpoint-kore-admin](../api/endpoint-kore-admin.md): a route that disappears by configuration is
   one a deploy check cannot tell apart from a broken deployment.

## 3. Code anchors

| Service | Code |
|---|---|
| kore-library | `kore-build/src/main/kotlin/io/github/youndie/kore/gradle/` — the Gradle plugin and the generating task |
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/version/` — the type the generated object implements |
| kore-library | `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/VersionRoute.kt` — the route |
| sample-service | `samples/service/build.gradle.kts` — the plugin applied, on both targets |

## 4. Scenarios (BDD)

All but the last are automated. The last needs the agents, which is
[B-28](../backlog/B-28-observability-wiring.md).

### Scenario: the binary reports the commit it was built from
* **Given:** a build at a known commit with a clean working tree
* **When:** `GET /version` is called
* **Then:** the response names that commit
* **And:** it names a build timestamp
* **Automated:** kore-ktor `VersionRouteTest.the body names the release and the version and the
  commit and the build time` — the body is asserted whole, because a deploy check greps it; and
  kore-build `GitFactsTest.a clean repository reports its commit and is not dirty` for the value
  reaching it

### Scenario: a dirty tree is visible
* **Given:** a build with uncommitted changes
* **When:** `GET /version` is called
* **Then:** the commit is marked as dirty
* **Automated:** kore-ktor `VersionRouteTest.a dirty build says so in the commit`, and kore-build
  `GitFactsTest.an uncommitted change makes the tree dirty`

### Scenario: a build without git still produces a binary
* **Given:** a source tree with no `.git` directory
* **When:** the project is built
* **Then:** the build succeeds
* **And:** `GET /version` reports the commit as `unknown`
* **Automated:** kore-build `GitFactsTest.a directory that is not a repository reports unknown`, and
  the two ways git gives no answer at all — not installed, and not answering — which reported an
  empty commit until a surviving mutation said so

### Scenario: changing the commit changes the compiled value
* **Given:** a built binary
* **When:** a commit is made and the project is rebuilt without cleaning
* **Then:** `GET /version` reports the new commit
* **And:** this is the scenario that fails when the generated file is a side effect rather than an
  input — rule 5
* **Automated:** kore-build `CompilationInputTest.a new commit makes the compilation out of date
  without cleaning`, against a real Kotlin Multiplatform project — the wiring is keyed on that
  plugin, so a simpler project would assert a branch that never runs

### Scenario: the same value reaches the agents
* **Given:** an observability agent is configured and no release override is set
* **When:** the process starts
* **Then:** the release the agents are given is the one `GET /version` reports

### Scenario: the reduction switch reduces the body and never removes the route
* **Given:** a deployment that does not want to publish its commit, with a `RELEASE` of its own
* **When:** `GET /version` is called
* **Then:** it answers `200` with the release name alone
* **And:** neither the commit nor the build time appears
* **And:** with the switch on and **no** `RELEASE`, the process refuses to start — kore's default
  release is `version+commit`, so reducing to it would publish the commit under a switch meant to
  hide it
* **Automated:** `VersionRouteTest` — four of its nine cases, including the refusal and the
  no-git case where there is no commit to hide

## 5. Out of scope

* **A build-information endpoint with dependencies, feature flags or configuration.** That is
  `--print-config`'s job, and it is a flag rather than a route for the reasons in
  [feature-typed-config](feature-typed-config.md) §3.
* **Signing or attesting the build.** `/version` says what the binary claims to be; it is not
  evidence about provenance.

## 6. Quirks

* **Generated source, not a resource — and the reason is platform, not preference.** Kotlin/Native
  has no JVM-style resource loading and no manifest. A resource-based implementation works on the
  JVM, compiles on native, and returns nothing there; that is the shape of bug this whole repository
  is written to avoid.
* **A timestamp makes a build non-reproducible, and that is accepted here with its eyes open.** Two
  builds of the same commit differ in one field. The alternative — the commit's own date — answers a
  different question ("when was this written") than the one asked during a rollout ("is this the
  image CI built twenty minutes ago"). If reproducibility becomes a requirement the timestamp is the
  field to drop, and that is a decision with a name rather than a surprise.
* **The timestamp is kept when the identity has not otherwise changed, and that is not an
  optimisation.** This paragraph used to end at reproducibility, which was the cheaper half of the
  cost. The generator deliberately re-runs on every build, so a wall-clock field changed the
  generated source on every build — and the generated source is a source of `commonMain`, so every
  build recompiled commonMain and, on Kotlin/Native, relinked the binary. The date now changes only
  when the version, the commit or the dirty flag does. What that costs is a tree nobody has committed
  to since its last build reporting when it was *first* built; such a tree already reports a `-dirty`
  commit, which says the same thing louder.
* **`unknown` is what a build from a source archive reports.** The images are built from artefacts
  Gradle produced outside Docker (`samples/service/Dockerfile` copies them in), so a container build
  is not one of these cases — but a build from a release tarball, or a CI checkout that fetches no
  git metadata, is. The value is honest; it is worth knowing which pipelines will show it.
