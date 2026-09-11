---
id: B-09
title: "The announce stage: readiness false, then wait"
status: done
priority: P0
size: S
stage: m2-shutdown
epic: feature-ordered-shutdown
blocked_by: [B-04, B-16]
---

# B-09 — The announce stage: readiness false, then wait

Flip the readiness gate, then wait the configured pre-drain delay before anything else happens.

- **The decision and its reason.** Research §1.10: during a rollout the control plane has already
  marked the terminating endpoint `ready: false`, so flipping readiness buys the truth for anything
  that polls the pod directly. What actually stops traffic arriving after `SIGTERM` is the *time* it
  takes a rule change to reach every node — so the wait is the stage that does the work, and giving
  it a name is what stops it being deleted as pointless.
- **Rejected:** hanging this off `ApplicationStopPreparing`. Research §1.2: on CIO that event fires
  after the listening socket has stopped accepting, so a probe arriving afterwards gets a connection
  refused rather than a 503, and the drain deadline is already counting.
- Does **not** cover: the default's value, which is a hypothesis until [B-20](B-20-pre-drain-default.md).

- AC: the stage flips the readiness gate and then waits, asserted over the transcript of the stage
  machine. **The end-to-end proof — A4 and A5 against a running container — is
  [B-39](B-39-kore-wired-sample.md)**, because it needs a sample that uses kore and there is not one:
  `samples/service` is the control and its whole value is being wired the ordinary way.
- Also `blocked_by` [B-16](B-16-check-registry.md), added 2026-09-12: a stage that sets readiness
  false needs somewhere for readiness to live. The original `blocked_by` named only the stage machine
  and would have had this item picked before anything it could announce existed.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/`

## Iteration 1 — 2026-09-12

**Done.** `ReadinessGate` and `AnnounceNotReady`, plus `announce { }` on the plan builder. 8 tests;
46 green on each platform.

**The gate has two inputs and they fail for different reasons** — the checks say whether the
dependencies answered, the latch says whether the process has started stopping. A verdict that
collapsed them would report a draining pod and a broken database identically, so the reasons stay
separate and a test asserts there are two of them when both are true.

**The latch is one-way.** A gate that could go back to ready would let a process halfway through
closing its pools advertise itself to a load balancer, which is the exact opposite of what the stage
is for.

**`@Volatile` rather than an atomic, and the reason is that nothing needed more.** A compare-and-set
would let `beginShutdown` report whether *this* call flipped it — and nothing wants to know, because
the sequence runs exactly once by the stage machine's own guard. `kotlin.concurrent.atomics` is also
still experimental in common code (`kotlin.concurrent.AtomicInt` is Native-only, which is what the
first attempt used and what the compiler said no to). What a latch genuinely needs is that a write on
one thread is seen by a reader on another.

**Mutations, all killed, all test failures rather than compilation failures:**

| Mutation | Result |
|---|---|
| the announce stage does not flip the gate | 3 of 46 red |
| the verdict ignores the latch | 4 of 46 red |
| an unhealthy check does not shut the gate | 3 of 46 red |
| the announce stage stops dwelling | 2 of 46 red |

The last is the one that matters: the flip takes microseconds and **the wait is the work**. A stage
that did only the flip would pass every assertion about readiness and defeat the purpose
(research §1.10).

**No scenario becomes `**Automated:**`.** "Readiness falls before the drain begins" is written against
`GET /health/ready` and a running container — my test asserts the gate is shut *from inside the drain
participant*, which is the same claim one layer down, not the same test. It stays manual until
[B-39](B-39-kore-wired-sample.md).

**A rule of this repository broken by its author:** a test name carried a comma, which Kotlin/Native
refuses and the JVM accepts. It is in `CLAUDE.md`, it was written there after B-08 hit it, and it
still took a red native compile to notice.
