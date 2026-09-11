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

- AC: assertions A1, A2 and A3 hold on both platforms; a configuration with `timeout <= grace` is
  refused at startup and the message names both numbers.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/`
