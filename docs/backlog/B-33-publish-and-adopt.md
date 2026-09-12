---
id: B-33
title: "Publish kore and adopt it in the first consumer"
status: question
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

## The question — this item is three decisions, and two of them are not the loop's

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
