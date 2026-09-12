---
id: endpoint-kore-admin
title: The routes kore mounts
type: api_endpoints
status: draft
services:
  - kore-library
  - sample-service
contract_source:
  - "kore:kore-ktor ProbeResponse"
  - "kore:kore-ktor VersionResponse"
parent_feature: feature-health-probes
---

# API: the routes kore mounts

> **Not built yet** — `status: draft`. This is the contract kore will serve, decided in
> [research-architecture](../research/research-architecture.md) D4 and D7. When the routes exist the
> status changes and every line below is re-read against the handler.

> The **complete** reference for everything kore adds to a consumer's routing tree. It is five
> routes and there will not quietly be a sixth: a library that mounts routes a service did not ask
> for is a library that can widen its own attack surface in a patch release, so the list here is the
> list, and a route added to it is a backlog item with a reason.

## Routes — all of them, no exceptions

| Method and path | Service | Auth tier | In the generated schema? | Purpose |
|---|---|---|---|---|
| `GET /health/startup` | any kore consumer | none | **no** | Has the process finished coming up? Answers `503` until every startup gate has completed, `200` afterwards, and never goes back to `503`. |
| `GET /health/ready` | any kore consumer | none | **no** | Is this process willing to be sent traffic? `200` only while the process is serving **and** every registered dependency check last answered healthy. `503` from the moment the shutdown sequence begins. |
| `GET /health/live` | any kore consumer | none | **no** | Is this process wedged? Answers `200` from the moment the server is listening, and `503` only for a condition a restart would fix. **Reads no dependency, ever.** |
| `GET /health` | any kore consumer | none | **no** | Alias of `/health/live`. Exists because every chart in the portfolio names it today; a rename that breaks a running deployment to gain a nicer URL is not worth it. |
| `GET /version` | any kore consumer | none | **no** | The commit, the build timestamp and the version this binary was built from — [feature-build-identity](../features/feature-build-identity.md). |

**Why none of them is authenticated, written down rather than assumed.** A kubelet probe carries no
credential and cannot be given one; a readiness route behind a token reports the state of the
authentication machinery rather than of the process. The containment is the network: these routes are
on the service port inside the pod network, and a chart that publishes that port to the internet has
published the application with it. The one route where this is a real decision rather than a
necessity is `/version` — see the quirk below.

**Why none of them is in a generated schema.** They are not part of any product's API; a generated
client that offers `getHealthReady()` is noise in every consumer. The column is kept because its
absence is exactly the kind of thing that silently becomes false.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| `GET /health/startup`, `/health/ready`, `/health/live`, `/health` | `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeRoutes.kt` |
| `GET /version` | `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/VersionRoute.kt` |
| the state all four read | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/health/` |

## Request and response bodies

No route takes a request body or a parameter.

Response shapes live in the contract classes named in `contract_source` and are not copied here. Two
properties of them are contract rather than detail, so they are stated:

* **A probe's body is never the reason it passed.** The kubelet reads the status code; the body
  exists for a person with `curl`. A consumer that parses a probe body is coupling to something kore
  reserves the right to make more useful.
* **A failing readiness body names which check failed and how long ago it was refreshed.** A probe
  that answers `503` with no attribution is one an operator has to reproduce by hand, and the point
  of a dependency check is to have done that already. The *age* is there because the result is
  cached (see the quirk below) and a stale healthy answer and a fresh one are different facts.

## Errors

| Condition | Status | Body |
|---|---|---|
| Startup gates not finished | `503` | which gates are outstanding |
| Startup finished | `200` | — |
| Ready, all checks healthy and fresh | `200` | — |
| A dependency check last answered unhealthy | `503` | the failing check's name, its message, and the age of the result |
| A dependency check has not answered within its refresh budget | `503` | the check's name and the age of the last result — a stale result is not a healthy one |
| The shutdown sequence has begun | `503` | `"shutting down"` |
| The process is serving | `200` on `/health/live` | — |
| A request refused during the drain | `503` **with `Connection: close`** | `"shutting down"` |

The last row is the one the oracle checks (assertion A3 of
[research-oracle](../research/research-oracle.md) §2.3) and it applies to **every** route in the
application, not only to kore's own — it is what the drain does to anything that arrives after the
announce stage.

## Quirks

* **`/health/startup` is one-way.** Once it has answered `200` it never answers `503` again, even
  during shutdown. It is not a status; it is a latch. A startup probe that can fail later would
  restart a pod that is trying to shut down cleanly, which is the opposite of the point — and since
  Kubernetes runs the startup probe only at startup (research §1.10), a `503` there after the fact is
  a value nothing reads and everything misreads.
* **`/health/ready` answers from a cache.** The dependency checks run on a background loop with their
  own timeouts, and the route reads the last result. This is deliberate and it is Risk 3 of the
  research: a check on the request path can hang, and a readiness probe that hangs is a readiness
  probe whose `timeoutSeconds` — default 1 — decides the answer. The cost is that a failure is
  detected one refresh interval late, which is stated in the body as an age rather than hidden.
* **`/health` is an alias and will stay one.** It is the liveness answer, not the readiness one.
  A chart that points its readiness probe at `/health` gets a probe that never fails while the
  process is alive — which is what the first consumer does today (research §1.11) and exactly what
  kore exists to stop. `--print-config` prints the probe block a chart should carry, so the correct
  one is available without reading this file.
* **`/version`'s body is a contract; the probes' bodies are not.** A probe body is for a person with
  `curl`. This one is read by deploy checks, so it is `key: value` per line, plain text, in a fixed
  order: `release`, `version`, `commit`, `built`, and `compiled-release` **only** when the
  environment names a release that disagrees with the compiled one. Lines may be added; those names
  do not change. The disagreement line appears only when there is a disagreement, on purpose — a
  line that is always there is one nobody reads.
* **A reduction that would reduce nothing is refused at startup.** kore's default release is
  `version+commit`, because a deploy marker and a crash group need to tell two builds of one version
  apart. So `KORE_VERSION_REDUCED` with no `RELEASE` would serve the commit under a switch whose
  purpose is to hide it — a deployment that believes it is private and is not, which is the same
  shape as an observability endpoint configured without its key. A build with no git has no commit to
  hide and is allowed.
* **`/version` is public and that is a decision with an escape hatch.** For a public repository a
  commit hash is not a secret and the route earns its keep in every deploy check. For a private one,
  a hash plus a build timestamp narrows down what is running. kore therefore reads a single switch
  that reduces `/version` to the release name alone — the same value a chart already puts in
  `RELEASE` — rather than removing the route, because a route that disappears by configuration is one
  a deploy check cannot distinguish from a broken deployment.
