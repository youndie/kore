---
id: B-30
title: "Decide whether kore owns the entry point"
status: question
priority: P2
size: S
stage: m5-wiring
blocked_by: [B-33]
---

# B-30 — Decide whether kore owns the entry point

Open question 2 of [research-architecture](../research/research-architecture.md) §3. Everything is
currently written as "kore mounts routes into your application and wraps your `EmbeddedServer`". The
alternative is that kore *is* the entry point: one call builds the server, installs the routes, reads
the configuration and runs the sequence.

- **The hypothesis.** Both, with the wrapper as the supported path and the pieces public. That is
  closer to the brief's "one line" without making the pieces unavailable to a service that has its own
  reasons.
- **Why it cannot be settled on paper.** A single sample always agrees with the API it was written
  against. The disagreement that decides this is a *second* consumer, which is why this item sits
  behind adoption rather than before it.
- **Rejected:** deciding now to avoid rework. The rework is one file; the wrong answer is an API.

- AC: a decision recorded here, with the second consumer's experience as the evidence;
  [services/kore-library](../services/kore-library.md) §2 updated to match.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/`
