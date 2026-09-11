---
id: B-21
title: "The schema DSL and the reader"
status: wip
priority: P0
size: M
stage: m4-config
epic: feature-typed-config
---

# B-21 — The schema DSL and the reader

A typed declaration — name, type, required or defaulted, secret or not, and pairs that must be set
together — read once, before anything serves.

- **The decision and its reason.** One source: the environment. Every additional source is a rule
  about which of two values wins, which is a new way to be wrong about what is running. The value of
  the feature is the schema and the refusal, not the plumbing.
- **Rejected:** a configuration framework with files and precedence. Explicitly a non-goal.
- A boolean means the exact string `true`; anything else, `"True"` included, means false, and an unset
  switch always means the closed position. A security switch that opens on a misspelling is one that
  ships open. This is the first consumer's rule already; kore makes it the type's behaviour rather
  than a comment beside each `==`.

- AC: a schema with a required field, a default, a secret and a declared pair reads correctly and
  refuses correctly; failure messages name the variable.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/`
