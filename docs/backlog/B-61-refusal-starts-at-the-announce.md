---
id: B-61
title: "The 503 refusal starts at the announce instead of the drain"
status: done
priority: P1
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-61 — The 503 refusal starts at the announce instead of the drain

Found by [B-60](B-60-jvm-ktor-hook-stops-the-announce.md) and left open there: with `--pre-drain=5000`
the oracle's A5 failed on **both** the linuxX64 binary and the JVM jar (2891–2989 ms < 5000 ms). So
the platform did not matter. Reported again from keel's side as
[#94](https://github.com/youndie/kore/issues/94), which this item closes: A5 at −81 ms on every keel
run, and on the JVM 1–5 of 46 new-connection probes refused from about 1 s into the announce.

## Why

`samples/service` installed `installShutdownRefusal(isShuttingDown = { readiness.isShuttingDown })`.
`ReadinessGate.beginShutdown()` is what `AnnounceNotReady` calls, in the **announce**. So every request
after the signal was answered `503` while the announce was supposed to be serving it. The README's
shape of the promise, D6 and `KoreStage.DRAIN` all say only the drain refuses.

Three things made that line the obvious one to write:

- `installShutdownRefusal` took a predicate and deliberately left the gate undecided
  ([B-10](B-10-drain-stage.md): "that is the announce stage's business, B-09"). B-09's text never
  mentions the refusal, and B-39 wired the sample;
- `ReadinessGate.isShuttingDown`'s KDoc said it was *"what the drain refusal is gated on"*;
- feature rule 4 and research §1.13 consequence 2 said *"gated on the sequence having begun"*, and
  the announce is part of the sequence.

**It did not stay in the sample.** Every kore consumer found copied the line, ten services in all.
The public ones are [keel](https://github.com/youndie/keel), [tracy](https://github.com/youndie/tracy),
[katcher](https://github.com/youndie/katcher), [shildik](https://github.com/youndie/shildik),
[metrik](https://github.com/youndie/metrik) and [xyk](https://github.com/youndie/xyk). The
`native-service-bootstrap` skill in [kotlin-skills](https://github.com/youndie/kotlin-skills) copied
it too.

**Why A5 passed until now:** it measures from readiness falling to the first refusal, and the first
refusal arrives when a driver's in-flight three-second request comes back and it sends the next one.
With the two-second default wait, that moment is already in the drain. Only a wait longer than the
requests could see it. When the wait was longer, each driver got `503` + `Connection: close`,
reconnected, and was refused again, for the rest of the announce: 2 500–7 400 exchanges after the
signal across B-60's five-second runs. The JVM's late A8 refusals in B-60 happened inside that storm.

## Where the gate lives

**A latch of its own, `DrainGate`, in `kore-core` next to `AnnounceNotReady`. `EngineDrain` opens it
as its first act, before `stopSuspend`, and `installShutdownRefusal(drain: DrainGate)` reads it.**

- **Not readiness with a second meaning.** The two flip in different stages and tell a client
  opposite things: readiness says "stop sending", the announce keeps answering what still arrives,
  and only the drain says "go away". One flag for both is the defect.
- **Inside `EngineDrain` rather than as a participant of its own.** Participants within a stage run
  concurrently ([B-53](B-53-stopping-the-registry-does-not-wait.md)), and "refuse, then stop the engine"
  is an order. It also removes a registration a consumer could forget.
- **In `kore-core`, not `kore-ktor`**, because the gate is not HTTP-specific. Something that refuses
  work in the drain without Ktor reads the same latch.
- Checked against D2 (stages with names; the gate adds no stage and no deadline) and D6 (the refusal
  still promises the header, not the socket). Neither is changed. Rule 4 of feature-ordered-shutdown
  and research §1.13 consequence 2 are amended where they stand, because their wording is where the
  wrong gate came from.

**Deprecated, not removed:** the predicate overload of `installShutdownRefusal` and the three-argument
`EngineDrain`. Both still compile and behave as before. The warning names the fix, so the ten services
above see it when they bump kore. A consumer that only moves `EngineDrain` to the four-argument form
and keeps its predicate on readiness still refuses through the announce. The deprecation message on
the predicate is the only thing that says so.

**Rejected: exposing the running stage from the sequence** (`sequence.hasEntered(DRAIN)`). The
refusal is installed in the module, before the sequence exists, so it would need a late-bound
reference to get there. A latch both sides are handed is simpler and is how readiness already works.

## Verified

- **`RefusalOpensAtTheDrainTest`** (commonTest, run on jvm and linuxX64): a real CIO engine, the real
  sequence with `AnnounceNotReady` and `EngineDrain`, and HTTP spoken over raw sockets. In the announce,
  `/work` answers `200` on an open connection and on a new one, and readiness answers `503`. In the
  drain, the same open connection gets `503` + `Connection: close`.
- **Mutations**, each killed on **both** targets by the named assertion (run with `--continue`, result
  files deleted first; see below):

| Mutation | jvm | linuxX64 |
|---|---|---|
| `EngineDrain` opens the gate at construction | `refused on an open connection during the announce … expected 200 but was 503` | same |
| `EngineDrain` never opens the gate | timed out waiting for the drain | same |
| the test wired like the sample: `installShutdownRefusal(isShuttingDown = { readiness.isShuttingDown })` | `refused on an open connection during the announce` | same |

  The first pass reported linuxX64 green for the first mutation. It was not: the JVM task failed
  first, Gradle stopped, and the linuxX64 result file was the previous green run's. Hence `--continue`
  and deleting the result files before each run.
- `./gradlew build` on the Linux box: `BUILD SUCCESSFUL`, 138 result files' tests, 0 failures.
- **The oracle**, 2026-09-27, Linux box, `--work=3000 --connections=8`, one fresh port per run (never
  used in the session, and absent from `ss -tan`). Native is the **release** executable; B-60's native
  rows were the debug one.

| subject | pre-drain | A5 | A8 — new connections inside the announce | exchanges after the signal | exit |
|---|---|---|---|---|---|
| JVM jar, **before** (B-60) | 5000 | FAIL 2891 / 2989 ms | 45 and 46 of 47 | 4 092 / 4 231 | 143 after ~20.1 s |
| native, **before** (B-60) | 5000 | FAIL 2905 / 2923 ms | 47 of 47 | 6 957 / 6 833 | 0 after ~20.1 s |
| JVM jar | 2000 | PASS 2924 / 2882 ms | 17, 18 — all answered | 16 / 16 | 143 after 17.08 s |
| native | 2000 | PASS 2944 / 2952 ms | 17, 17 — all answered | 16 / 16 | 0 after 17.06 s |
| JVM jar | 5000 | PASS 5910 / 5993 ms | 46, 46 — all answered | 24 / 24 | 143 after 20.08 s |
| native | 5000 | PASS 5941 / 5944 ms | 46, 47 — all answered | 24 / 24 | 0 after 20.07 s |

  All eight runs: 10 passed, 0 failed, 0 inconclusive. **A5 is now the pre-drain wait plus about
  0.9 s**, which is what it should be: the first refusal is the first request a driver sends after
  the drain began, and that waits for its previous three-second request to come back. A3 counts
  **8 refusals per run**, one per driver, where B-60's runs had thousands of exchanges. That fits the
  drain's listener having stopped accepting, so a refused driver cannot reconnect (research §1.2).
  That mechanism was not checked separately. The JVM's late A8 refusals are gone with the storm. The
  exit times equal B-60's for the same wait, so moving the refusal adds nothing to the shutdown.

## #94: keel's load shape, and what it found in the oracle

keel's setup is `--path=/items --connections=32 --pre-drain=5000`, with a route that answers in about
1 ms. So the refusal storm starts at the signal instead of three seconds later. The same shape was
run against kore's own sample (`--work=1 --connections=32`), with `main` as the control and this
branch as the fix. keel ran twice: as its `fix/busy-port-refusal` branch (kore 0.1.6, refusal on
readiness), and as a throwaway copy of it on this branch's kore through `mavenLocal`, with
the two-line `DrainGate` wiring. The copy's `lib/` carries `kore-ktor-jvm-0.1.7-b61.jar`, and it
compiles `installShutdownRefusal(draining)`, which 0.1.6 does not have. Same box, fresh port per
run, two runs a cell, under the final oracle below:

| subject | platform | A5 | A8 misses | 503 refusals |
|---|---|---|---|---|
| sample, control (`main`) | JVM | FAIL at most 51 / 36 ms | **3 / 5 of 46** | 4 579 / 8 001 |
| sample, control (`main`) | native | FAIL at most 19 / 1 ms | **1 of 46** / 0 of 43 | 5 379 / 19 238 |
| sample, B-61 | JVM | PASS 4925–5021 / 4949–5052 ms | 0 of 46 / 0 of 45 | 101 / 32 |
| sample, B-61 | native | PASS 4915–5005 / 4911–5014 ms | 0 of 46 / 0 of 45 | 32 / 32 |
| keel, control (0.1.6) | JVM | FAIL at most 27 / 11 ms | **2 / 4 of 46** | 4 314 / 6 837 |
| keel, control (0.1.6) | native | FAIL at most 17 / 15 ms | 0 of 43 / 0 of 42 | 19 160 / 19 266 |
| keel, B-61 | JVM | PASS 4933–5024 / 4924–5017 ms | 0 of 46 / 0 of 45 | 129 / 32 |
| keel, B-61 | native | PASS 4922–5018 / 4911–5011 ms | 0 of 46 / 0 of 46 | 32 / 32 |

Every B-61 run: 10 passed, 0 failed. Every control failed A5, and 5 of the 8 controls also failed A8.
A first pass of the same matrix, before the A5 change below, gave the same A8 picture: 0 misses in
8 B-61 runs, 2 JVM control runs with one miss each.

**What the A8 misses were here: `java.net.BindException: Cannot assign requested address`** — the
*client* failing to get a local port, not the server refusing. The build box's ephemeral range is
52810–56905, about 4 000 ports, and a control run collects 4 300–19 300 refusals in five
seconds, each followed by a reconnect. keel saw `ConnectException: Connection refused` instead, and
that was **not** reproduced here. Both appeared only under the storm, and neither appeared in 16
runs without it. So the JVM A8 misses of #94 and B-60 are not a finding of their own. Also,
native is not immune here (one control miss). Why the JVM missed more often was not investigated.

**Two runs refused more than once per driver** (101 and 129 against 32): some drivers reconnected
after their refusal and were refused again. All of it was inside the drain, so A5 and A8 are
unaffected. It suggests the JVM listener accepts for a moment after the drain begins. Recorded, not
investigated.

### Two changes to the oracle

- **`ProbeSample` keeps `Exchange.failure`**, and a failing A8 prints it with every miss's time.
  `status == null` covered a refusal, a reset and a timeout alike. keel had to patch the oracle to
  learn which, and the table above could not have been written without it. The old A8 text said
  "the listener closed before the drain", a cause the probe could not see. It was wrong for #94,
  where the misses interleaved with answered probes.
- **A5 reads the readiness bracket, as A4 has since B-57.** Measured from the first non-`200`
  sample, the gap was short by the poller's lag, up to one 100 ms interval. While the refusal
  started at the announce, nobody noticed. With the refusal at the drain, a 1 ms route is refused at
  *fall + wait + ε*, and every correct run failed by 39–92 ms: 4908–4961 ms on the first pass. The
  lower edge of the bracket is the later of the last `200` and the signal, since readiness cannot fall
  before the process is told to stop. research-oracle §2.3 has the three answers, and why the middle
  one is a PASS here and NOT_APPLICABLE in A4. `A5BracketTest`, five cases. Mutations, each killed
  by the named case: the strict reading restored — `a refusal at the drain boundary passes although
  the sample was late`; the signal bound dropped — `the fall is bounded by the signal and not only
  by the last healthy sample`.

- AC: during the announce a request is served on open and new connections, and the refusal begins
  with the drain. A unit test fails if it does not. The oracle is green on A5 and A8 for JVM and
  native at 2000 and 5000 ms, two runs each. **Met**, and re-run under the final oracle with the same
  result (8 of 8, 10 passed each). #94's acceptance: A5 and A8 green at 5000 ms on JVM and native, on
  the sample and on keel's `/items`. **Met** (table above).
- **Not done here:** the ten consumers. Each needs a kore release and a two-line change. A release
  waits for #92 (B-60) by the owner's decision, and this branch is stacked on it.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/DrainGate.kt`,
  `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ShutdownRefusal.kt`,
  `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/EngineDrain.kt`,
  `kore-ktor/src/commonTest/kotlin/io/github/youndie/kore/ktor/RefusalOpensAtTheDrainTest.kt`,
  `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Assertions.kt`,
  `samples/oracle/src/test/kotlin/io/github/youndie/kore/oracle/A5BracketTest.kt`,
  `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/KoreWiring.kt`
