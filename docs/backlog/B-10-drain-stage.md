---
id: B-10
title: "The drain stage and the 503 that says not to come back"
status: done
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

- AC: the refusal answers `503` with `Connection: close` once the sequence has begun, and a
  configuration with `timeout <= grace` is refused at startup with both numbers in the message.
  **The end-to-end proof — A1, A2 and A3 against a running container — is
  [B-39](B-39-kore-wired-sample.md)**; there is no sample that uses kore yet.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/`

## Iteration 1 — 2026-09-12

**Done.** `installShutdownRefusal`, `EngineDrain` and `KoreRoutes` in `kore-ktor`. 10 tests green on
`jvm` **and** `linuxX64` — the native half matters here, because the refusal is the thing research
§1.13 says has to exist and `ktor-server-test-host` publishes for all three native targets (checked in
Central before depending on it from a common test source set).

**The exemption list turned out to be the load-bearing half.** A `503` from `/health/live` is a
failed liveness probe, and enough of those restart the pod **in the middle of the shutdown it is
reporting**. Readiness is exempt for a different reason — it is *supposed* to fail during a shutdown
and its own answer says which check did, where a blanket refusal reaches the same status code saying
nothing.

**A mutation survived, and what it exposed was the test rather than the code.**

| Mutation | First run | After |
|---|---|---|
| drop `finish()` | **survived** — every test green | 1 of 11 red |
| stop exempting liveness | 1 of 10 red | |
| allow `timeout == grace` | 1 of 10 red | |
| drop the `Connection: close` header | 1 of 10 red | |

The test aimed at `finish()` asserted that the *route* does not also run — and routing does not run a
handler for a call whose response has already been sent, so the route was never the thing at risk.
**Everything else in the pipeline is:** a plugin at a later phase — logging, metrics, a tracing span,
anything a service installs — goes on running for a request refused before it began, against exactly
the resources the shutdown is closing. The new test intercepts a later phase, and the production
comment now says what the line actually protects instead of what it was assumed to.

This is the second time in this repository that a passing test turned out to assert the wrong thing,
and both times mutation is what said so.

**Deliberately not done:** where the shutdown state lives. `installShutdownRefusal` takes a predicate,
so this item does not decide that — it is the announce stage's, [B-09](B-09-announce-stage.md). And
the end-to-end proof of A1–A3 against a running container is [B-39](B-39-kore-wired-sample.md), which
is why this item's acceptance was narrowed before it was taken.
