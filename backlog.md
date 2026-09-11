# kore — backlog

## The goal

One library that makes every Kotlin server binary in the portfolio behave the same way at the two
moments nobody writes tests for: when it comes up, and when it is told to stop. An ordered shutdown,
three probes that answer three different questions, a typed configuration that refuses to start on a
name it does not recognise, one call that wires the three observability agents, and a `/version` that
names the commit.

Native-first: the servers are Kotlin/Native binaries, and every fact that makes this library
necessary is invisible from the JVM.

## The reality check

There is **no code**. This repository currently holds the research, this backlog and the layer
documents, and every layer document says `status: draft` because that is what it is. The entry point
is [docs/research/research-architecture.md](docs/research/research-architecture.md), and the second
thing to read is [docs/research/research-oracle.md](docs/research/research-oracle.md) — the
acceptance experiment, written before the implementation on purpose.

Three things about this backlog that are not obvious from the items:

* **M1 is not scaffolding.** It ends with the oracle run against a service built the *ordinary* way
  ([B-03](docs/backlog/B-03-negative-control.md)) — a negative control. If that does not break, the
  premise of the whole library is wrong, and week one is the cheap time to learn it.
* **Two gates, and neither replaces the other.** The property test over the stage machine
  ([B-13](docs/backlog/B-13-property-test.md)) cannot see a duration set to zero, because zero is a
  legal duration. The end-to-end oracle ([B-06](docs/backlog/B-06-oracle-harness.md)) cannot see
  anything inside the release stage. The gap in each is named in research-oracle §3.3 rather than
  discovered later.
* **Three items are `question`, not `open`.** [B-25](docs/backlog/B-25-undeclared-grace-period.md),
  [B-29](docs/backlog/B-29-profiler-hook.md) and [B-30](docs/backlog/B-30-entry-point-question.md)
  change behaviour a consumer can observe, and the answer is the owner's to pick. B-29 — the
  profiler hook — may well be answered by dropping it, and saying so now is why it is a question.

## Stages

| Stage | Name | What closes it |
|---|---|---|
| `m0-shape` | Shape | a build that produces the modules on the four targets, and a documentation gate that runs |
| `m1-oracle` | The oracle first | the stage machine, the two samples, the harness — and the negative control run |
| `m2-shutdown` | The ordered shutdown | the five stages for real, the property test proved by mutation, the booblik adapter |
| `m3-probes` | Three probes | the registry, the three routes, two real checks, and the pre-drain default measured |
| `m4-config` | Typed configuration | the schema, per-target enumeration with its honest gap, `--print-config`, the unknown-variable refusal |
| `m5-wiring` | Wiring and identity | the Gradle plugin, `/version`, the three agents in one call, and two questions answered |
| `m6-release` | Release | published, adopted by the first consumer, measured, and the upstream findings filed |

A milestone closes as a whole and gets a line here saying what came out beyond the plan and which
research hypothesis was confirmed or refuted.

### M1 closed — 2026-09-11

**The premise of the library is confirmed by experiment, and it is narrower than it was written.**
Same source, same configuration, same load: with a stop subscriber that closes a resource an
in-flight request uses, the JVM is fine and Kotlin/Native returns **48 responses in 5xx on every
run** ([the matrix](docs/research/measurements-2026-09-11/negative-control.md), 18 runs). Research
§1.1 is no longer a reading of two source files.

**One hypothesis refuted, and it was §1.1's own.** It asked whether an in-flight call is cancelled by
`disposeAndJoin()` on Native and said the consequence was the same either way. It is not: every
in-flight request completes there. The danger is the *subscriber* closing a pool, not Ktor killing
the request — a smaller claim, and a true one. [B-14](docs/backlog/B-14-inflight-hypothesis.md)
closed a milestone early and by a different item than planned.

**Three things came out that were not in the plan:**

* **CIO refuses nothing while it drains — it keeps serving, for the whole grace period.** 48 requests
  after the signal on already-open connections, no `503`, no `Connection: close`. So kore's refusal
  is a plugin kore installs, and the drain deadline is the shutdown duration under load rather than a
  ceiling. New research §1.13; it reshaped two feature documents.
* **Ktor's default grace period is 1000 ms**, so an unconfigured service drops in-flight work on both
  platforms for a reason unrelated to ordering — a simpler justification for the library than the one
  it was founded on, and one that hides the founding one underneath it.
* **CI compiled nothing.** [B-07](docs/backlog/B-07-native-link-cost.md) was raised to P0 out of
  numeric order once the loop was allowed to merge its own green pull requests: "green" meant the
  documentation gate. Two pull requests had already merged on that signal.

**Two facts about other people's code, both found by resolving rather than reading**, which cost a
decision and produced two questions: `booblik-native` exists and D5's premise did not
([B-36](docs/backlog/B-36-booblik-adapter-targets.md)), and none of the three observability agents is
on Maven Central ([B-37](docs/backlog/B-37-agents-not-on-central.md)).

**What the stage cost, measured:** CI build 1m48s cold and 1m39s with `~/.konan` warm, 2m33s once the
sample's release link joined it; `linkReleaseExecutableLinuxX64` 35.3 s against 1.4 s for debug;
images 47.7 MB native and 493 MB JVM.

**The uncomfortable one.** Three separate times an experiment silently measured nothing — a control
whose subscriber closed nothing, a stale image, and a separator that fed an argument to the wrong
parser. Each looked consistent and convincing. All three were caught by reading output rather than
exit codes, and two are now rules in `CLAUDE.md`.

## Index

<!-- BEGIN INDEX - generated by scripts/backlog_index.py, do not edit by hand -->

## Open (31)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-09](docs/backlog/B-09-announce-stage.md) `[ ]` | The announce stage: readiness false, then wait | P0 | S | B-04, B-16 |
| [B-10](docs/backlog/B-10-drain-stage.md) `[~]` | The drain stage and the 503 that says not to come back | P0 | M | B-04 |
| [B-11](docs/backlog/B-11-release-stage.md) `[ ]` | The release stage: three groups, three deadlines | P0 | M | B-04 |
| [B-13](docs/backlog/B-13-property-test.md) `[ ]` | The property test over the stop order, proved by mutation | P0 | M | B-04 |
| [B-16](docs/backlog/B-16-check-registry.md) `[ ]` | The check registry, the cached result and the refresh loop | P0 | M | - |
| [B-17](docs/backlog/B-17-probe-routes.md) `[ ]` | The three routes and the /health alias | P0 | S | B-16 |
| [B-21](docs/backlog/B-21-config-schema.md) `[ ]` | The schema DSL and the reader | P0 | M | - |
| [B-22](docs/backlog/B-22-environment-enumeration.md) `[ ]` | Environment enumeration per target, and the honest macOS gap | P0 | M | B-21 |
| [B-23](docs/backlog/B-23-print-config.md) `[ ]` | --print-config: values, origins, masks, and the probe block | P0 | S | B-21 |
| [B-39](docs/backlog/B-39-kore-wired-sample.md) `[ ]` | The sample wired with kore, and the oracle run that proves the stages | P0 | M | B-09, B-10, B-11, B-17 |
| [B-12](docs/backlog/B-12-deadlines-must-fit.md) `[ ]` | Refuse at startup a sequence that cannot fit the grace period | P1 | S | B-11 |
| [B-15](docs/backlog/B-15-booblik-adapter.md) `[ ]` | The booblik participant: flush, then close | P1 | S | B-11, B-36 |
| [B-18](docs/backlog/B-18-pooled-store-check.md) `[ ]` | The pooled-store check runs a statement, not an acquire | P1 | S | B-16 |
| [B-20](docs/backlog/B-20-pre-drain-default.md) `[ ]` | Measure and settle the pre-drain default | P1 | S | B-09 |
| [B-24](docs/backlog/B-24-unknown-variable-refusal.md) `[ ]` | Prefix scoping and the unknown-variable refusal | P1 | S | B-22 |
| [B-26](docs/backlog/B-26-build-identity-plugin.md) `[ ]` | The Gradle plugin that generates the build identity | P1 | M | B-01 |
| [B-28](docs/backlog/B-28-observability-wiring.md) `[ ]` | The three agents in one call, with three shutdown contracts | P1 | M | B-11, B-21, B-37 |
| [B-33](docs/backlog/B-33-publish-and-adopt.md) `[ ]` | Publish kore and adopt it in the first consumer | P1 | M | B-13, B-17, B-23, B-28 |
| [B-36](docs/backlog/B-36-booblik-adapter-targets.md) `[?]` | Decide kore-booblik's target set — D5's premise is gone | P1 | S | - |
| [B-37](docs/backlog/B-37-agents-not-on-central.md) `[?]` | Decide how a public kore resolves the three agents | P1 | S | - |
| [B-38](docs/backlog/B-38-native-dispatcher.md) `[ ]` | Decide where kore's background work runs on Kotlin/Native | P1 | S | B-04 |
| [B-19](docs/backlog/B-19-broker-check.md) `[ ]` | The broker check asks for metadata, not for a socket | P2 | S | B-16 |
| [B-25](docs/backlog/B-25-undeclared-grace-period.md) `[?]` | Decide what kore assumes when the grace period is not declared | P2 | XS | B-12 |
| [B-27](docs/backlog/B-27-version-route.md) `[ ]` | GET /version and its reduction switch | P2 | XS | B-26 |
| [B-30](docs/backlog/B-30-entry-point-question.md) `[?]` | Decide whether kore owns the entry point | P2 | S | B-33 |
| [B-31](docs/backlog/B-31-first-consumer-findings.md) `[ ]` | Findings against the first consumer, found by reading | P2 | S | - |
| [B-32](docs/backlog/B-32-file-upstream.md) `[ ]` | File the upstream proposals that are ready; ask before Ktor | P2 | S | B-03 |
| [B-34](docs/backlog/B-34-the-three-numbers.md) `[ ]` | The three numbers, measured as comparisons | P2 | S | B-06 |
| [B-29](docs/backlog/B-29-profiler-hook.md) `[?]` | Decide the shape of the profiler hook, or drop it | P3 | S | - |
| [B-02](docs/backlog/B-02-documentation-gate.md) `[ ]` | The documentation gate in CI | infra | S | - |
| [B-35](docs/backlog/B-35-draft-gate.md) `[ ]` | Turn on the draft gate on the default branch | infra | XS | B-13, B-17, B-23, B-27, B-28 |

## Closed (8)

**Shape**

- [B-01](docs/backlog/B-01-repository-skeleton.md) `[x]` - Repository skeleton and the multiplatform build

**The oracle first**

- [B-03](docs/backlog/B-03-negative-control.md) `[x]` - The negative control: run the oracle against the unfixed shape
- [B-04](docs/backlog/B-04-stage-machine.md) `[x]` - The stage machine and its recorded transitions
- [B-05](docs/backlog/B-05-sample-service.md) `[x]` - The sample service: one source, two binaries
- [B-06](docs/backlog/B-06-oracle-harness.md) `[x]` - The oracle harness: load, signal, assertions
- [B-07](docs/backlog/B-07-native-link-cost.md) `[x]` - Measure what a native link costs CI, and decide the gate's cadence

**The ordered shutdown**

- [B-08](docs/backlog/B-08-signal-handling.md) `[x]` - Signal handling: a handler that only sets a flag
- [B-14](docs/backlog/B-14-inflight-hypothesis.md) `[x]` - Settle what disposeAndJoin does to an in-flight call on Native

<!-- END INDEX -->
