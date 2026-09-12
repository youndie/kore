---
id: B-33
title: "Publish kore and adopt it in the first consumer"
status: done
priority: P1
size: M
stage: m6-release
blocked_by: [B-13, B-17, B-23, B-28, B-43]
---

# B-33 — Publish kore and adopt it in the first consumer

Publish `io.github.youndie:kore-*` and replace konekt's hand-written lifecycle with it.

- **The decision and its reason.** A library with one sample has been tested against the API it was
  written against. konekt is the disagreement: a real service, on the JVM, with a broker, a pool,
  three agents and a chart — and with three of the defects kore exists to prevent
  ([B-31](B-31-first-consumer-findings.md)).
- **Rejected:** adopting before the oracle is green on both platforms. A first consumer that finds the
  ordering wrong finds it in production.
- Does **not** cover: a native consumer. konekt is a JVM service, and kore's harder platform is
  therefore still covered only by the sample. That is a stated gap, not an oversight, and the next
  consumer should be one of the native services.

## Done 2026-09-12: published, and adoption proposed rather than performed

**kore `0.1.0` is published** to the portfolio's repository and **verified by reading it back** —
`kore-core`, `kore-ktor`, `kore-observability` and their per-target variants all answer `200` for
their POM. A green publish task is not a published artefact: the token here is scoped to artifact
paths rather than to the group, so a new coordinate can be refused while its neighbour succeeds
(this portfolio has met that), and the repository can accept a PUT and serve nothing. It did not
happen — every path was accepted — and it was checked rather than assumed.

`.github/workflows/publish.yaml` is the repeatable route: manual dispatch, `0.1.<run number>` by
default, `make build` **before** the publish because `publish` does not depend on `check` having
passed, and the same read-back as a step. It needs `REPOSILITE_USER` and `REPOSILITE_SECRET` as
repository secrets — youndie/kore has none today, so until somebody adds them the workflow will fail
loudly at the PUT rather than quietly publish nothing.

**The repository block is registered unconditionally**, even with no credentials, and that is
deliberate: a sibling in this portfolio guarded its block on the secret being set, the block then
registered no repository at all, and the publish went green having done nothing.

**Adoption is proposed, not performed** — [youndie/konekt#35](https://github.com/youndie/konekt/issues/35),
which is what the owner chose. It names what kore replaces, what it closes
([konekt#30](https://github.com/youndie/konekt/issues/30) and
[#32](https://github.com/youndie/konekt/issues/32)), what it does **not**
([#31](https://github.com/youndie/konekt/issues/31), a booblik call konekt makes itself), and the
two costs a reader would otherwise find out later: every configuration variable gains the schema's
prefix, and konekt is a JVM service so adopting kore here exercises the half that was never in doubt.

## The question, before it was answered

Picked on 2026-09-12 and stopped immediately, because doing it means three different kinds of act:

1. **Making kore publishable** — coordinates, version, `maven-publish`, the POM, the JVM floor. kore
   declares *none* of these today. This needs nobody's permission and is now
   [B-43](B-43-publishing-setup.md).
2. **Publishing a version.** A version number other builds resolve cannot be taken back, it needs
   credentials for the portfolio repository, and "which version is the first one" is a release
   decision rather than a build one.
3. **Rewriting the first consumer's lifecycle.** Replacing konekt's `ApplicationStopping` subscriber,
   its hand-written `fromEnv()` and its single `/health` is a large change to a running service, in
   another repository, whose review is not this backlog's.

The boundary is the same one [B-31](B-31-first-consumer-findings.md) met and the owner drew there:
this loop works kore's backlog. Filing findings against konekt was answered with "issues"; rewriting
konekt is a different size of act, and publishing is irreversible in a way nothing else here is.

**What is wanted:** a yes for (2) with a version number, and a decision on who does (3) — this loop
against konekt, or konekt's own work with kore's documents as the specification. (1) proceeds either
way.

- AC: konekt's `ApplicationStopping` subscriber, `KonektConfig.fromEnv()` and single `/health` are
  replaced by kore; its chart carries the three probes; `/version` answers; the three numbers of
  [B-34](B-34-the-three-numbers.md) are taken before and after.
- Anchors: `konekt/server/src/main/kotlin/io/konekt/`, `konekt/charts/konekt/`
