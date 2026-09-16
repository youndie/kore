---
id: B-54
title: "The oracle can only be pointed at kore's own sample"
status: done
priority: P1
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-54 — The oracle can only be pointed at kore's own sample

Reported from a consumer as [#81](https://github.com/youndie/kore/issues/81). `samples/oracle` wrote
the driven path into the harness — `/work?ms=`, which is `samples/service`'s route and nobody
else's — so the one thing that makes kore's central claim checkable could only ever be checked
against kore's own sample.

That is backwards from where the risk is. The library's own suites cover the library; what a consumer
adopting kore gets wrong is the **wiring** — a pool closed in `ApplicationStopping`, a
`runUntilSignal` installed before the server is serving, a participant that ignores cancellation — and
the oracle is precisely the instrument that would catch those. Its alternatives were to ship an
on-demand sleep in a production service, or to write a second copy of the assertions, which is how
two specifications come to disagree.

`--path` defaults to `/work?ms={work}`, so nothing changes for `samples/service`.

**The probe paths stay hardcoded**, as the report suggested: `/health/ready` and `/health` are what
`installKoreProbes` mounts, so they are kore's contract rather than the subject's choice, and a
consumer that moved them has a different problem.

## And the oracle had been unable to start its own sample for four days

Found by running it, which is the only way this class of thing is found. The first run of this branch
failed with `the container never answered /health`, and the container's own log said why:

```
the SAMPLE configuration is not usable:
  - SAMPLE_POOL_DSN: is required and is not set
```

[B-50](B-50-sample-uses-the-config-schema.md) gave the sample a schema with a required key, so the
kore arm refuses to start unconfigured — which is the feature working. `Container` gained environment
support in that same change and `measure` was taught to pass it; **this harness was not**, and nothing
said so, because the oracle is invoked by name and is in neither `build` nor `check`. So `--env`
arrives here too, for the same reason `--path` does: an oracle that knew which variables
`samples/service` needs would be the same defect one level down.

**Nothing runs the oracle.** That is a deliberate trade recorded in `samples/oracle/build.gradle.kts`
— it needs Docker, drives containers, and takes a minute — and the bill for it arrived twice now: once
as [B-39](B-39-kore-wired-sample.md)'s stale image, and once as four days of a harness that could not
start its subject. What would change it is a job with Docker that builds the image and runs the
oracle on a schedule; what that costs is a red build nobody in this repository caused, on the same
argument the anchors report is not a gate. Not decided here.

## Measured, both ways

| Run | Result |
|---|---|
| `--image=kore-sample:native --work=3000 --env=SAMPLE_POOL_DSN=…` | **8 passed, 0 failed**, A5 not applicable — the run the harness has always made, working again |
| the same image, `--path=/version` | **6 passed, 0 failed**, A3, A4 and A5 not applicable — 223 034 exchanges |

The second is the shape a consumer with fast routes meets, and it is worth naming because it is not
what the report predicted. A closed loop keeps a request outstanding on every connection whatever the
route costs, so in-flight at the signal is still 8 and **A1 and A6 — the central claims — still
assert**. What weakens is A3 and A4: a request that was never refused carries no `Connection: close`
and dates no readiness fall, so both become `NOT_APPLICABLE` rather than wrong. A consumer wanting
those two needs a route that is still in flight when the signal lands.

- AC: the oracle drives a path given on the command line, and `samples/service` runs unchanged
  without one. **Met**, both measured above.
- AC: the subject's environment can be supplied. **Met** — and it had to be, or the first AC could not
  be demonstrated.
- Anchors: `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Oracle.kt`,
  `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Run.kt`
