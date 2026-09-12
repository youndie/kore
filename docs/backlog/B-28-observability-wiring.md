---
id: B-28
title: "The three agents in one call, with three shutdown contracts"
status: done
priority: P1
size: M
stage: m5-wiring
epic: feature-observability-wiring
blocked_by: [B-11, B-21, B-37]
---

# B-28 — The three agents in one call, with three shutdown contracts

Install tracy, metrik and katcher from one call, configured through the schema, with the shutdown
treatment each actually needs.

- **The decision and its reason.** Research §1.6, read in the agents: tracy can flush and is never
  asked to, metrik stops itself without flushing, katcher cannot stop at all. A service that wires
  them by copying an example gets whichever of the three behaviours the example happened to get right.
- **A half-configured agent is a refusal at startup.** All three answer a missing value by doing
  nothing, in three different ways — "a deployment that believes it is observed and is not". The rule
  is taken from the one place in the portfolio that already gets it right, not invented.
- **Installing a buffer without the thing that empties it must be impossible through kore.** tracy's
  plugin and its delivery are two objects; installing only the first logs into memory and reports
  nothing.
- **Blocked on [B-37](B-37-agents-not-on-central.md), found during B-01:** none of the three agents
  is on Maven Central, so there is no way to depend on them that a consumer outside the portfolio can
  resolve. That is a decision before it is a wiring job.
- Does **not** cover: the losses kore cannot fix from outside — metrik's open window, katcher's
  un-stoppable scope. Those are documented in
  [feature-observability-wiring](../features/feature-observability-wiring.md) §7 and filed in
  [B-32](B-32-file-upstream.md).

- AC: the scenarios of feature-observability-wiring §5 hold, including that an unreachable tracy
  endpoint does not delay the exit past the telemetry deadline.
- Anchors: `kore-observability/src/commonMain/kotlin/io/github/youndie/kore/observability/`

## The build host went offline, and it hid a real defect behind an outage

Part-way through this item the tunnel stopped answering, and the native half went unverified. It
looked like an environment problem and was partly a real one — but when the host came back, the
native suite still did not finish. **`TracyFlushTest` was hanging on Kotlin/Native**, and had been
hanging on CI at the same time: over thirty minutes in both places, against eighteen seconds for the
other two suites.

It nested `testApplication { }` inside `runTest { }`. Two test scopes, one of them driving a real
socket, and a virtual clock in a test whose subject is a network round trip. Rewritten with real
engines and the work inside `Dispatchers.Default`, it takes fourteen seconds on `linuxX64`.

Worth recording because the outage was a *plausible* explanation that happened to be wrong, and
"the machine was down" would have closed the question. The test that was never confirmed on the
target platform is precisely the one that turned out to be broken there.

**Verified locally on both targets after the host returned:** 14 tests on `jvm` and 14 on
`linuxX64`, read from the result files, plus the mutation re-killed on both.

## Findings

* **A surviving mutation found the rule the feature is named after.** Installing tracy's *plugin*
  without its *delivery* passed every test in the module: the plugin is visible from outside and the
  delivery is not, so asserting the plugin proves only that a buffer was installed. That is exactly
  rule 7's failure and exactly what the portfolio's most complete service does. The fix is
  `TracyFlushTest`, which points tracy at a **real receiver on a real socket** and asserts a request
  arrived at another process — nothing in it is a double.
* **kore was about to hand back a wiring a service could not log through.** `installKoreObservability`
  kept the `TracyAgent` to itself, which leaves a consumer with the plugin's sampled request spans
  and no way to write a line. Found while writing the test above — the test needed a logger and there
  was no way to get one. The agent is now returned, which makes tracy part of this module's API
  surface rather than an implementation detail, and that is honest for a module named after three
  agents.
* **The keys are handed to the consumer's schema rather than kept in one of kore's.** A
  `ConfigSchema` owns a prefix and the unknown-variable refusal is scoped to it; two schemas would
  mean a variable that is unknown to one and declared by the other. So `ObservabilityKeys.all` and
  `.pairs` splice into the service's single schema, and every variable is prefixed like everything
  else — `APP_RELEASE`, not `RELEASE`.
* **Which corrects a claim B-27 made.** Its `KoreKeys.RELEASE` comment said kore reads the
  portfolio's existing unprefixed `RELEASE`. It cannot: `ConfigSchema.variableOf` is
  `"${prefix}_${name}"` and there is no escape. An unprefixed key would also sit outside the typo
  check by construction, and that check is the config feature's whole point. The cost is one line in
  a chart, paid once at adoption.
* **What kore still cannot do, confirmed rather than assumed.** `MetrikConfig` has no way to opt out
  of the plugin's own `ApplicationStopping` subscription — only `enabled = false`, which turns metrik
  off entirely — and the plugin constructs its agent internally, publishing only the counters. So
  kore has no handle to stop and no way to move the moment. On Kotlin/Native that moment is *before*
  the drain. `MetrikAgent` and `UdpSender` are public, so kore *could* construct the agent and
  reimplement the plugin's measurement hooks — and will not: "not an observability library, it wires
  three agents and reimplements none of them" is a stated non-goal, and a fork of somebody else's
  plugin is the most expensive way to break it.
