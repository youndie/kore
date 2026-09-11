---
id: B-06
title: "The oracle harness: load, signal, assertions"
status: done
priority: P0
size: L
stage: m1-oracle
epic: feature-ordered-shutdown
blocked_by: [B-05]
---

# B-06 — The oracle harness: load, signal, assertions

The load driver and the assertions A1–A7 of [research-oracle](../research/research-oracle.md) §2.3,
plus the three vacuity guards of §2.5.

- **The decision and its reason.** Every assertion is made from the **client's** record and the
  process's exit, never from the server's log. A log line is written by the code under test, and an
  oracle that trusts it can be satisfied by a comment.
- **Rejected:** asserting that the server closed the socket. Research D6: on CIO that would pass
  because of the 45-second idle timeout or the grace-period cancellation — for a reason unrelated to
  the code under test.
- Does **not** cover: multiple replicas, `SIGKILL`, or timing thresholds. Wall-clock numbers are
  [B-34](B-34-the-three-numbers.md), measured as comparisons.
- The vacuity guards are part of this item, not a follow-up: a run where nothing was in flight must
  report **inconclusive**, not pass.

- AC: the harness fails when nothing was in flight at the signal; it fails when the process never
  saw the signal; it distinguishes a `500` from a `503`; it records response headers per request.
- Anchors: `samples/oracle/`

## Iteration 1 — 2026-09-11

**Done, and it changed the research more than it changed the code.**

`samples/oracle`: a hand-written HTTP/1.1 client on a raw socket, a closed-loop driver, a readiness
poller on its own connection, container control through `docker`, and assertions A1–A6 plus the three
vacuity guards of research-oracle §2.5. A7 is not here: it compares two runs, so it belongs to
whatever drives both.

The client is hand-written because a library client hides the three things every assertion turns on —
whether the connection was **reused**, whether the body **completed**, and what the server said about
`Connection` on the way out.

`NOT_APPLICABLE` and `INCONCLUSIVE` are first-class verdicts and both exit non-zero. A question that
was not answered is not a pass.

**Three findings, in order of how much they cost:**

1. **CIO does not refuse anything while it drains — it keeps serving, for the whole grace period.**
   48 requests after the signal, none refused, no `503`, no `Connection: close`, and ~20.5 s to exit
   for ~3 s of outstanding work. So "stop accepting" stops new *connections*, not new *requests*;
   kore's `503` is a plugin kore installs, and the drain deadline is the shutdown duration under load
   rather than a ceiling. New research §1.13; [B-10](B-10-drain-stage.md) now owns the plugin.
2. **Ktor's default `shutdownGracePeriod` is 1000 ms**, so an unconfigured service cuts off every
   request slower than a second — on both platforms, for a reason unrelated to ordering. And at that
   setting **the oracle cannot see the ordering question at all**: the first run reported "8 of 8 cut
   off" on native, which looks exactly like §1.1's prediction and is not it. research-oracle §1 now
   requires the control at two grace periods.
3. **§1.1's hypothesis is refuted.** With a grace period long enough to observe, all eight in-flight
   requests completed on Kotlin/Native. Whatever `disposeAndJoin()` cancels, the in-flight calls are
   not it — the danger is a subscriber closing a pool, not Ktor killing the request.
   [B-14](B-14-inflight-hypothesis.md) is closed by this, a milestone earlier than planned and by a
   different item than planned.

**The harness's own first run was wrong, and it is recorded rather than quietly fixed.** A4 reported
**PASS** against a subject that has no `/health/ready` at all, because "no readiness endpoint" was
written as *every* poll returning 404 — and the polls taken after shutdown return `null`. An
assertion that passes where it has no subject is precisely the failure mode this repository exists to
refuse. Found by reading the output of the first run instead of the exit code.

**Not done:** A7 (two runs, so it belongs to the caller) and the negative control's write-up, which
is [B-03](B-03-negative-control.md) — this item built the instrument and proved it works by making it
report a failure, a not-applicable and an inconclusive.
