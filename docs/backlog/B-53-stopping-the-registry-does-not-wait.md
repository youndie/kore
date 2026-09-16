---
id: B-53
title: "Stopping the check registry does not wait for the check in flight"
status: done
priority: P2
size: S
stage: m6-release
epic: feature-health-probes
blocked_by: []
---

# B-53 — Stopping the check registry does not wait for the check in flight

Found by a consumer, filed as [kore#79](https://github.com/youndie/kore/issues/79).

`HealthRegistry.stop()` cancelled the refresh loop and returned. Cancelling says the loop **must**
stop; it does not say it **has**. A check is arbitrary user code, and in practice it is a statement
against the very resource a later stage is about to release — so between "told to stop" and
"stopped" there is a window in which the check is still holding what the next stage closes. On
Kotlin/Native that window is not narrow: a check inside an FFI call or a blocking driver is somewhere
cancellation does not reach, which `refreshOnce`'s own `withTimeoutOrNull` already says it cannot
interrupt.

There was no way to wait from outside either. `loop` is private and `start` returns `Unit`, so a
caller could not join what it had started. The workaround available to consumers was to give the
registry a scope of nothing but itself and join that scope instead of calling `stop()` — which
works, costs a scope that exists only to be joinable, and is not discoverable from the API.

**The consumer's symptom, because it is the argument for the deadline mattering.** A webhook gateway
whose readiness check is `SELECT 1` against a SQLite pool: the release stage stopped the checks, the
stage after it closed the pool and took a truncating WAL checkpoint, and that checkpoint has to wait
for every other connection to the database. The in-flight `SELECT 1` was one of them. It surfaced as
`RELEASE_CONSUMERS DEADLINE_EXCEEDED in 3.000228711s` on a CI runner, in roughly one push in ten.

- **Decision: a second method, not a changed one.** `stop()` is published API, and a suspending
  version of it under the same name would break every caller on a patch release. `stopAndJoin()`
  says in its name what the other one omits — the same shape as the booblik rule elsewhere in this
  repository, where the missing verb was *wait* rather than *send*.
- **Decision: `stop()` is deprecated at WARNING rather than deleted or left alone.** Two methods
  that differ in a way nobody can see from the call site is how this defect recurs; a deprecation
  with `ReplaceWith` names the difference at the call site, and a caller that genuinely does not
  care can suppress it — two of this repository's own tests do, because not waiting is the thing
  they measure.
- **Rejected: returning the `Job` from `start`.** It would let a caller join, but only after
  cancelling it themselves, so the API would hand out the pieces of an operation instead of the
  operation. It also makes the registry's internals the caller's business for no gain.
- Not covered: the sample. Its readiness has no dependency check to stop, and the sample is the
  oracle's subject — changing what that binary does at shutdown changes what the acceptance
  experiment measures.

- AC: a caller can wait for the checks to have ended, and the wait is bounded by the stage's own
  deadline rather than outliving it. **Met** — a stage that runs out of time cancels its
  participants, and `stopAndJoin` suspends, so the join is cut short there.
- AC: a test that fails if the join is removed. **Met** — `stopAndJoin waits for a check that
  cancellation cannot reach` in `HealthRegistryTest`, on jvm and linuxX64, with the contrast test
  beside it. Shown able to fail: replacing `cancelAndJoin` with `cancel` fails that one test and
  only that one.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/HealthRegistry.kt`,
  `kore-core/src/commonTest/kotlin/io/github/youndie/kore/health/HealthRegistryTest.kt`

## The test hung before it failed, and that was worth more than the fix

The first version of the guard was made to fail on purpose — the discipline of §3.3 — by replacing
`cancelAndJoin` with `cancel`. It did not go red. It **hung**, and the suite had to be killed.

The reason is the same `NonCancellable` that makes the test faithful: a block nobody releases cannot
be cancelled by `runTest` either, so an assertion that throws before the gate is opened leaves a
child coroutine that cleanup waits for for ever. A guard whose failure mode is a hang is worse than
the defect it guards — on CI it reads as a stuck runner, which is the one failure people retry
instead of reading.

Fixed by opening the gate in a `finally`. The mutation then fails one test in six seconds.
