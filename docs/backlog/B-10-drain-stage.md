---
id: B-10
title: "The drain stage and the 503 that says not to come back"
status: open
priority: P0
size: M
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-04]
---

# B-10 — The drain stage and the 503 that says not to come back

Call `EmbeddedServer.stop(grace, timeout)` from kore, and answer anything arriving after the announce
stage with `503` plus `Connection: close`.

- **The decision and its reason.** kore calls `stop` itself rather than letting a hook do it, because
  the announce stage needs somewhere to run *before* the engine starts stopping and no Ktor event
  offers that (research §1.2, D3).
- **Rejected:** promising that the connection is closed. Research §1.4: CIO decides keep-alive from
  the **request's** `Connection` header and writes the response headers verbatim, so the header is a
  promise to the client and nothing more. Asserting more would be asserting the idle timeout.
- Also here: validate `timeout` against `grace` at startup. CIO's hard-kill window is
  `timeout - grace`, so a pair with `timeout <= grace` cancels and then does not wait — a shape best
  refused rather than discovered.

- **The refusal is kore's, and that is measured rather than assumed** *(added by
  [B-06](B-06-oracle-harness.md), 2026-09-11)*. Ktor refuses nothing while it drains: with a grace
  period long enough to observe, CIO served **48 further requests on already-open connections** after
  `SIGTERM` and refused none. "Stop accepting" stops new *connections*, not new *requests*. So this
  item also builds the plugin that answers `503` + `Connection: close` once the sequence has begun —
  without it there is no refusal anywhere and A3 has no subject.
- **And kore sets `shutdownGracePeriod` itself.** The default is **1000 ms**, shorter than a great
  many real requests, and at that setting in-flight work is cut off on both platforms for a reason
  that has nothing to do with ordering. kore derives both numbers from its own stage deadlines and
  never inherits them.

- AC: assertions A1, A2 and A3 hold on both platforms; a configuration with `timeout <= grace` is
  refused at startup and the message names both numbers.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/`
