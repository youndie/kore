---
id: feature-health-probes
title: Three probes that answer three questions
type: feature
status: draft
owner: unassigned
involved_services:
  - kore-library
  - sample-service
client_entries: []
api:
  - endpoint-kore-admin
tags: [kubernetes, probes, readiness]
---

# Three probes that answer three questions

> **`status: draft`** — designed, not built. The Kubernetes behaviour it is built on is verified and
> sourced in [research-architecture](../research/research-architecture.md) §1.10; the baseline it
> improves on is §1.11.

## 1. Overview

A kore service serves `GET /health/startup`, `GET /health/ready` and `GET /health/live`, and they
answer three different questions. Startup: *has the process finished coming up?* Ready: *is it
willing to be sent traffic, and did its declared dependencies answer?* Live: *is it wedged?*

The thing being replaced is one route answering `200` because the process is alive, used for all
three — which is what the portfolio's most complete service does today (research §1.11), with a chart
comment correctly arguing that a probe must not read the store and then pointing readiness at the
same route. The cost of that arrangement is not theoretical: a pod whose database is unreachable
reports itself ready and receives traffic it cannot serve, and a pod that is slow to start is
restarted by a liveness probe rather than waited for.

## 2. Business rules

1. **Liveness reads no dependency. Ever.** A liveness probe that reads the database restarts a
   healthy pod during a database blip — and then the next one, and the next — which is how a
   dependency outage becomes an outage of everything in front of it. The only thing that may fail a
   liveness probe is a condition a restart would fix.
2. **Readiness is the only probe that reads dependencies**, because its failure is the cheap one:
   traffic stops, nothing is killed (research §1.10).
3. **Startup is a latch.** Once it has answered `200` it never answers `503` again. Kubernetes runs
   the startup probe only at startup, so a later `503` there is a value nothing reads and everything
   misreads.
4. **Readiness answers from a cached result**, refreshed by a background loop with per-check
   timeouts. A check on the request path can hang, and a probe that hangs has its answer decided by
   `timeoutSeconds`, whose default is 1 (research §1.10). This is Risk 3 of the research.
5. **An absence says which absence it is.** A check with no result is `UNKNOWN` either way, but the
   reason is not the same fact: a registry whose first pass has not finished is a startup race that
   resolves, and a registry nobody started is a `503` for the life of the process. The body names the
   missing call in the second case, because every other signal there agrees with the wrong reading —
   the pod is running, the process is alive, liveness is `200` ([B-41](../backlog/B-41-nothing-starts-the-health-loop.md)).
6. **A stale result is not a healthy result.** If a check has not answered within its refresh budget,
   readiness is `503` and the body says how old the last answer is. A cache that keeps returning the
   last good value is a probe that reports health through an outage.
7. **A dependency check proves the dependency answered, not that a handle was produced.** For a pool
   this means running a trivial statement, because `acquire()` can hand out an idle connection whose
   far end is gone — and sqlx4k's pool has no `ping` to ask instead (research §1.9).
8. **Readiness goes false the moment the shutdown sequence begins**, before anything else happens —
   rule 1 of [feature-ordered-shutdown](feature-ordered-shutdown.md) §2.
9. **A failing readiness body names the check and the age of its result.** A `503` with no
   attribution is one an operator has to reproduce by hand; having already done that is the point of
   a dependency check.
10. **The refresh loop runs in a lane of its own**, `KoreDispatchers.checks`, and never on the thread
   the shutdown sequence uses. The cache keeps a blocking check off the *probe's* thread; it does
   nothing about the thread the check itself is holding, and on Kotlin/Native that is a thread kore
   owns. Research D9 has the decision and what it costs.

## 3. What a check is

A check has a name, a timeout, a refresh interval, and a suspending function that either returns or
throws. kore ships the registry and the loop; the checks themselves come from the service, with two
adapters in the box:

* **a pooled store** — run a trivial statement, bounded. Not `acquire()` (rule 6);
* **a message broker** — ask for metadata on a topic the service actually uses, bounded. Not "the
  socket is open", which answers the kernel: it cannot tell a broker that has finished starting from
  one that has not, one that knows this topic from one that does not, or a session still usable by
  this client from one the peer has closed. Research §1.15 has the verified version — neither booblik
  client reconnects, so a replaced broker pod is survived by the *consumer's* own code and detected by
  nobody.

  > This paragraph used to cite "research §1.8's neighbour finding" for a client left dialling
  > nothing forever. §1.8 contains no such finding, and the behaviour it described had already been
  > fixed in the first consumer. The design survived being checked; the reason for it did not, and
  > §1.15 replaces it.

A check that has never run is not healthy. The registry's initial state is "unknown", which readiness
reports as `503` — so a process that came up before its first refresh does not get a free `200`.

## 4. The numbers, and where they come from

The defaults are derived rather than chosen, and every one of them is stated with its derivation so
that changing it is an argument rather than a taste.

| Setting | Default | Derivation |
|---|---|---|
| readiness refresh interval | 2 s | half the readiness probe period below, so a probe rarely reads a result older than one period |
| dependency check timeout | 1 s | shorter than the refresh interval, so a slow check produces a stale result rather than a backlog of overlapping checks |
| pre-drain wait | 5 s | the floor of research §1.10: readiness `periodSeconds` × `failureThreshold` — 2 × 3 = 6 s of probing in the worst case is already covered by the control plane marking the endpoint `ready: false`, so what remains to cover is rule propagation to every node. Five seconds is the portfolio's first estimate and is **explicitly a hypothesis** until the oracle measures it (research-oracle §4) |
| drain deadline | 15 s | what is left of a 30 s grace period after the pre-drain wait and the release groups, with margin. **Read it as "how long shutdown takes under load", not as a ceiling** — research §1.13 measured CIO spending the whole grace period whenever a keep-alive client is still connected |
| release deadline, per group | 3 s | three groups, so 9 s worst case |

Sum: 5 + 15 + 9 = 29 s against the Kubernetes default `terminationGracePeriodSeconds` of 30. Since
the drain is spent in full under load, that is also the **ordinary** shutdown duration of a busy pod,
not a worst case. That is deliberately tight, and it is the reason rule 9 of
[feature-ordered-shutdown](feature-ordered-shutdown.md) §2 makes kore refuse a configuration that
does not fit: the defaults fit the default, and any service that raises one number must raise the
grace period too.

## 5. Code anchors

| Service | Code |
|---|---|
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/` — the registry, the cached result, the refresh loop |
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/Check.kt` — the contract |
| kore-library | `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeRoutes.kt` — the three routes and the alias |
| sample-service | `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/Main.kt` — one real check |

## 6. The probe block a chart should carry

Written here so that a consumer does not derive it, and printed by `--print-config` so that a
consumer does not have to find this file:

```yaml
startupProbe:
  httpGet: { path: /health/startup, port: http }
  periodSeconds: 1
  failureThreshold: 60          # one minute to come up; liveness stays out until this passes
livenessProbe:
  httpGet: { path: /health/live, port: http }
  periodSeconds: 20
  failureThreshold: 3
readinessProbe:
  httpGet: { path: /health/ready, port: http }
  periodSeconds: 2
  failureThreshold: 3
terminationGracePeriodSeconds: 30
```

`failureThreshold: 60` with `periodSeconds: 1` rather than a long `initialDelaySeconds`: the pod
becomes ready as soon as it is ready, instead of at a fixed time that has to be set for the worst
case. That is the shape the first consumer arrived at after measuring a start-time change that a
five-second initial delay had made invisible (research §1.11) — it is inherited rather than invented.

## 7. Scenarios (BDD)

**All seven are automated (six as of B-18, the seventh with B-41).** The last one runs against a double that reproduces the
documented failure rather than a real driver — that distinction is on the scenario itself, and a real
store in the sample is [B-40](../backlog/B-40-sample-pooled-store.md). A scenario gains its line when a test covers **all** of
it; where a clause is a consequence rather than a second observable, the scenario says so instead of
quietly counting it.

### Scenario: a registry nobody started says so rather than looking like a startup race
* **Given:** a service that registers dependency checks and never starts the refresh loop
* **When:** `GET /health/ready` is called
* **Then:** the response is `503`
* **And:** the body names `HealthRegistry.start` as the call that is missing, rather than reporting a
  first pass that has not finished — a state that would resolve, and this one does not
* **Automated:** `ProbeRoutesTest` (jvm and linuxX64), with `HealthRegistryTest` covering the
  distinction that makes it worth saying: a registry that *was* started and has not finished its
  first pass still reports a startup race, and stopping one is not un-starting it

### Scenario: the process is up but its store is unreachable
* **Given:** the service has started and its pooled store is unreachable
* **When:** `GET /health/ready` is called
* **Then:** the response is `503`
* **And:** the body names the failing check
* **And:** `GET /health/live` answers `200` — the process is not wedged, and restarting it would not
  help
* **Automated:** `ProbeRoutesTest`

### Scenario: liveness survives a dependency outage
* **Given:** every dependency check is failing
* **When:** `GET /health/live` is polled for longer than the liveness failure threshold
* **Then:** it answers `200` throughout
* **And:** the container is not restarted — which is the kubelet's consequence of the line above
  rather than a second thing to check; no test of a process can observe it
* **Automated:** `ProbeRoutesTest`

### Scenario: a check that hangs produces a stale result, not a hung probe
* **Given:** a registered check whose function never returns
* **When:** `GET /health/ready` is called after the refresh budget has passed
* **Then:** the response is `503` within the probe's own timeout
* **And:** the body reports the age of the last result
* **Automated:** `ProbeRoutesTest`

### Scenario: startup does not un-latch
* **Given:** `GET /health/startup` has answered `200`
* **When:** a dependency later fails, and later still the shutdown sequence begins
* **Then:** `GET /health/startup` answers `200` in both cases
* **Automated:** `ProbeRoutesTest`

### Scenario: a process that has not run its first check is not ready
* **Given:** the server is listening and no check has completed yet
* **When:** `GET /health/ready` is called
* **Then:** the response is `503`
* **Automated:** `ProbeRoutesTest`

### Scenario: a pool that hands out a dead connection is not healthy
* **Given:** a pool whose `acquire()` succeeds from its idle set while the server on the far end is
  gone
* **When:** the readiness check runs
* **Then:** it fails, because it ran a statement rather than taking a handle
* **And:** the same pool, in the same state, passes an `acquire`-shaped check — which is the whole
  difference, and is asserted in the same test
* **Automated:** `PooledStoreCheckTest` — against a double that reproduces the documented failure, not
  a real driver; a real store is [B-40](../backlog/B-40-sample-pooled-store.md)

## 8. Out of scope

* **Deciding what a good check is for someone else's dependency.** kore runs checks and reports them;
  whether `SELECT 1` is a good proxy for "this database will serve my queries" is a question for the
  service that owns the query.
* **A metrics endpoint.** That is metrik's, and it arrives over UDP rather than by scraping.
* **Readiness gates, pod conditions, and anything that needs the API server.** kore is a process, not
  a controller.

## 9. Quirks

* **`/health` remains an alias of `/health/live`.** Every chart in the portfolio names it. A chart
  that points readiness at it gets a probe that cannot fail while the process is alive — which is
  exactly today's behaviour and exactly what this feature exists to stop. The mitigation is the
  printed probe block of §6 rather than a breaking rename.
* **The pre-drain default is a hypothesis with a number in it.** Five seconds is an estimate of rule
  propagation, not a measurement. It is written as a default because a library must ship one; it is
  written as a hypothesis here because the oracle has not run. If the measurement disagrees, the
  correction goes into research §1.10 at the point of divergence, not into a quiet edit of this
  table.
