---
id: feature-observability-wiring
title: The three agents, wired in one call
type: feature
status: active
owner: unassigned
involved_services:
  - kore-library
  - sample-service
client_entries: []
api: []
tags: [tracy, metrik, katcher, observability]
---

# The three agents, wired in one call

> **`status: active`** — designed, not built. What each agent actually does at shutdown is verified
> and sourced in [research-architecture](../research/research-architecture.md) §1.6.

## 1. Overview

One call installs structured logging to [tracy](https://github.com/youndie/tracy), metrics to
[metrik](https://github.com/youndie/metrik) and crash reporting to
[katcher](https://github.com/youndie/katcher), reads their configuration from the environment under
the same schema as everything else, refuses a half-configured agent, and gives each one the shutdown
treatment it actually needs.

The reason this is a feature and not a snippet is that *"delivered" keeps meaning "configured"*. The
three agents do not agree on how they stop — one can flush and is never asked to, one stops itself
without flushing, one cannot stop at all — and a service that wires them by copying an example gets
whichever of those three behaviours the example happened to get right.

## 2. Business rules

1. **A half-configured agent is a refusal at startup.** Endpoint without key, or key without
   endpoint, fails the start. All three agents answer a missing value by doing nothing, quietly, in
   three different ways — *"a deployment that believes it is observed and is not"*. This rule is
   taken from the one place in the portfolio that already gets it right (research §1.11), not
   invented here.
2. **An absent agent is an explicit decision, not a mistake.** Both variables unset means off, and
   that is fine. One of the two set means the deployment is wrong about itself.
3. **The service name is validated, because nothing else validates it.** There is no registration
   step in any of the three: a typo does not fail, it creates a phantom service that looks healthy
   and receives nothing.
4. **The release identifier is required when any agent is on.** Unset, katcher's own default is
   `Unspecified`, and a crash group named `Unspecified` is a crash nobody can act on. It is the same
   value `/version` serves — see [feature-build-identity](feature-build-identity.md) rule 4.
5. **The instance identifier defaults to the pod name and can be overridden.** Without it every
   instance of a rolling deploy is the same instance, and "which one is slow" stops being a question
   the data can answer. It is overridable because a chart that sets `HOSTNAME` to something else —
   which has happened in this portfolio — otherwise merges two pods into one.
6. **Each agent gets the shutdown it needs**, and the three are different. §3.
7. **Installing an agent that fills a buffer without the thing that empties it is not possible
   through kore.** tracy's plugin and its delivery are two objects and installing only the first
   logs into memory and reports nothing. kore installs both or neither.

## 3. The three shutdown contracts

Read in the agents, not assumed (research §1.6):

| Agent | What it offers | What kore does |
|---|---|---|
| **tracy** | `TracyDelivery.stop(grace)` — cancels the loop and makes one last bounded flush, written deliberately for exactly this moment | holds the delivery object and calls `stop` in the telemetry group of the release stage, with kore's own deadline |
| **metrik** | `MetrikAgent.stop()` — cancels the job and the scope, closes the sender and the dispatcher. **No flush.** The plugin subscribes it to `ApplicationStopping` itself | nothing to call. kore records that the open aggregation window is lost, and §7 says what that costs |
| **katcher** | `Katcher.start { }` and nothing else — no `stop`, no `flush` | nothing to call. A crash report in flight at exit may not be delivered; §7 |

**The finding that made this table worth writing.** The portfolio's most complete service constructs
tracy's delivery, calls `start`, and discards the reference — so `stop` cannot be called, and every
shutdown loses the last flush interval of records, including the records explaining the shutdown.
Nothing is wrong with either library. The wiring was written once, correctly enough to start, and
never revisited. That is the failure class this feature exists for, and it is carried as
[B-31](../backlog/B-31-first-consumer-findings.md).

## 4. Code anchors

| Service | Code |
|---|---|
| kore-library | `kore-observability/src/commonMain/kotlin/io/github/youndie/kore/observability/` — the one call, and the three contracts above |
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/` — the schema the agent settings are declared in |
| sample-service | **no call site.** The sample is the shutdown experiment, and its container must not depend on three agents being reachable; wiring it with every agent off would exercise the branch that does nothing. The end-to-end call site arrives with the first consumer — [B-33](../backlog/B-33-publish-and-adopt.md) |

## 5. Scenarios (BDD)

All five are automated. The tracy delivery scenario runs against a **real receiver on a real socket**
rather than a double, because the thing being proved is that records leave the process — and the
mutation that survived the double-free version of this suite was exactly "install the buffer and not
the thing that empties it".

### Scenario: an endpoint without its key refuses the start
* **Given:** `TRACY_ENDPOINT` is set and `TRACY_KEY` is not
* **When:** the process starts
* **Then:** it refuses to start and names both variables
* **Automated:** `ObservabilityKeysTest.an endpoint without its key refuses the start and names both
  variables` — the refusal is the schema's pair rule, so kore declares the pairs rather than
  re-checking them at the call

### Scenario: no agent configured is a valid deployment
* **Given:** none of the three agents has any variable set
* **When:** the process starts
* **Then:** it starts and serves, with observability off
* **And:** `--print-config` says so explicitly
* **Automated:** `ObservabilityKeysTest.no agent configured is a valid configuration` and
  `ObservabilityInstallTest.no agent configured installs and has nothing to stop`

### Scenario: tracy's last records are delivered
* **Given:** tracy is configured and records have been produced since the last flush
* **When:** the process receives `SIGTERM`
* **Then:** a flush is attempted before the process exits
* **And:** it is bounded by kore's telemetry deadline rather than by tracy's flush interval
* **Automated:** `TracyFlushTest.a record written before the stop is delivered by the telemetry stage`
  — a record is written through the agent kore hands back, and the assertion is that a request
  arrived at another process

### Scenario: a tracy endpoint that is unreachable does not delay the exit
* **Given:** tracy is configured to an address that does not answer
* **When:** the process receives `SIGTERM`
* **Then:** the telemetry group ends at its deadline
* **And:** the process still exits inside the grace period
* **Automated:** `ObservabilityInstallTest.an unreachable tracy endpoint does not delay the exit past
  the telemetry deadline`

### Scenario: the release identifier is required once an agent is on
* **Given:** katcher is configured and no release identifier is set
* **When:** the process starts
* **Then:** it refuses to start
* **And:** the refusal says what katcher would otherwise have called the crash group
* **Automated:** `ObservabilityInstallTest.an agent without a release refuses the start and says what
  the default would be`

## 6. Out of scope

* **Being an observability library.** kore wires three agents that exist and reimplements none of
  them. A service that needs a fourth wires it itself.
* **Deciding what to log or measure.** The agents' plugins instrument the routes; kore decides
  nothing about content.
* **The profiler.** Named in the brief and deliberately unresolved — open question 1 of research §3.
  It is not in this feature because it has no defined shape yet, and a feature document that
  described one would be describing an intention.

## 7. Quirks

* **metrik's open aggregation window is lost on every shutdown, and kore cannot fix it from
  outside.** The agent's `stop()` does not flush, and the window defaults to 60 seconds. So the
  metrics for the final partial window — including everything served during the drain — are never
  sent. kore's options are to reimplement the plugin or to ask upstream; the portfolio's rule is to
  ask upstream, and it is [research-upstream-proposals](../research/research-upstream-proposals.md)
  §2.
* **metrik stops itself at a moment kore does not control, and the moment differs per platform.**
  The plugin subscribes to `ApplicationStopping`, which research §1.1 shows fires *after* the drain
  on the JVM and *before* it on Kotlin/Native. So on a native binary the requests served during the
  drain are not measured at all — which are exactly the requests an ordered shutdown exists to
  protect. Also §2 of the upstream proposals.
* **katcher cannot be stopped and its scope outlives the application.** A crash during shutdown may
  not be uploaded before the process exits. On Kotlin/Native its hook is
  `setUnhandledExceptionHook`, chained onto the previous one, so at least it does not collide with
  the signal handling of [feature-ordered-shutdown](feature-ordered-shutdown.md) §3 — that was
  checked rather than assumed.
* **A phantom service is the failure this wiring cannot detect.** Rule 3 validates the *shape* of the
  service name; it cannot know that `konket-server` was meant to be `konekt-server`. The only thing
  that catches that is somebody looking at the data, which is why `--print-config` prints the name
  rather than only the fact that it is set.
