---
id: B-27
title: "GET /version and its reduction switch"
status: done
priority: P2
size: XS
stage: m5-wiring
epic: feature-build-identity
blocked_by: [B-26]
---

# B-27 — GET /version and its reduction switch

Serve the generated identity, and let a deployment reduce it to the release name alone.

- **The decision and its reason.** For a public repository a commit hash is not a secret and the route
  earns its keep in every deploy check. For a private one, a hash plus a build timestamp narrows down
  what is running.
- **Rejected:** removing the route by configuration. A route that disappears is one a deploy check
  cannot tell apart from a broken deployment — so the switch reduces the body and never the existence.
- **Rejected:** authenticating it. A deploy check runs before anything has a token, which is the case
  the route exists for.
- The release it reports is the same value the observability agents are given, so a disagreement
  between the compiled identity and an environment override is visible rather than impossible to see.

- AC: `/version` reports the compiled commit; with the switch on it reports only the release; the
  agents receive the same release value — **the third clause is not met here.** The value exists and
  has one owner (`releaseOf`, `KoreKeys.RELEASE`), which is the half this item can deliver; handing
  it to the agents is [B-28](B-28-observability-wiring.md), which is blocked on
  [B-37](B-37-agents-not-on-central.md).

## Findings

* **The reduction switch was unsafe by default, and a test found it.** kore's release is
  `version+commit` — a deploy marker and a crash group have to tell two builds of one version apart.
  A test written to assert that the reduced body leaks nothing failed against the reduced body
  leaking the commit, because with no `RELEASE` the release *is* the commit. The switch now refuses
  at startup rather than reducing to something that reduces nothing; a build with no git has nothing
  to hide and is allowed.

  The refusal is not invented here: it is the rule the first consumer already got right for an
  observability endpoint configured without its key (research §1.11 consequence 2), which kore
  generalises — *"a deployment that believes it is observed and is not"*, with "private" in place of
  "observed".
* **kore now declares configuration variables of its own**, `RELEASE` and `KORE_VERSION_REDUCED`, as
  `ConfigKey`s rather than reading them with `getenv`. A variable kore reads and `--print-config`
  does not print is the same gap the config feature exists to close, pointed the other way.
  `RELEASE` is unprefixed because that is the name the charts already set (§1.11).
* **`overridden` means *disagrees*, not *was set*.** A chart setting `RELEASE` to the value kore
  computed is agreement; reporting it as an override would bury the interesting case among the
  boring ones. A blank override is absent — that is what an unset chart value looks like after
  templating.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/VersionRoute.kt`
