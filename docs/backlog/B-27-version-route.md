---
id: B-27
title: "GET /version and its reduction switch"
status: open
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
  agents receive the same release value.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/VersionRoute.kt`
