---
id: B-25
title: "Decide what kore assumes when the grace period is not declared"
status: done
priority: P2
size: XS
stage: m4-config
blocked_by: [B-12]
---

# B-25 — Decide what kore assumes when the grace period is not declared

Open question attached to Risk 4 of [research-architecture](../research/research-architecture.md) §3.
kore refuses a set of deadlines that cannot fit the grace period — and outside Kubernetes nothing
tells it what the grace period is.

- **The hypothesis.** Assume the Kubernetes default of 30 s, say so in `--print-config` with origin
  `default`, and let a service override it. An assumption that is printed is one somebody can
  contradict; an assumption that is not printed is a constant.
- **Rejected:** refusing to start without one. A library that cannot run outside Kubernetes because it
  was not told a Kubernetes number is a library with a bad dependency on a deployment shape.
- **Rejected:** skipping the fit check when the period is unknown. That removes the guard in exactly
  the environment where nothing else provides one.
- This is a `question` rather than `open` because the answer changes behaviour a consumer can observe,
  and it is the owner's to pick.

- AC: a decision recorded here and implemented in [B-12](B-12-deadlines-must-fit.md); `--print-config`
  shows the assumed value and its origin.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/`

## Decided 2026-09-12: keep the assumption, and name which way it is wrong

**The hypothesis stands.** kore assumes 30 s, prints that it assumed, and lets a service override it.
The two alternatives keep the rejections they already had: refusing to start makes a library depend on
a deployment shape, and skipping the check removes the guard exactly where nothing else provides one.

**What the decision adds is the direction of the error, measured rather than argued.** 30 s is the
*largest* common budget, so the assumption is optimistic: where the real budget is smaller, the fit
check passes a plan that will be killed.

| | |
|---|---|
| `docker stop`, no `-t` | **10 s** — measured at 11 s wall clock against a container that ignores `SIGTERM` |
| kore's own default deadlines | `5 + 15 + 3 x 3` = **29 s** |

So a service on kore's defaults under plain `docker stop` is `SIGKILL`ed in the middle of its drain
— the exact failure this library exists to prevent — and the fit check stays silent, because it
compared against a number nobody gave it. That is not hypothetical: kore's **own sample** runs under
`docker`, and the oracle passes `--grace` explicitly for this reason.

**Why the assumption stays anyway.** kore cannot discover the real budget: nothing tells a process
what it is, on any platform kore targets. Assuming the *smallest* common budget instead would refuse
kore's own defaults in Kubernetes, which is the target environment and the one where 30 s is not an
assumption at all but the documented default. An assumption that is right where the library is aimed,
printed as an assumption, and paired with a stated failure mode is better than a false refusal in the
common case.

**What changes as a result:** nothing in behaviour, and one sentence in the documentation — the one a
reader outside Kubernetes needs. Declare the grace period. kore will not catch a plan that overruns a
budget it was never told about.

## How it stood before the decision — 2026-09-12

[B-12](B-12-deadlines-must-fit.md) had to do *something* when it is not told the grace period, so it
does what this item's leading hypothesis says: assumes the Kubernetes default of 30 s, and **prints
that it assumed**, both in `ShutdownDeadlines.describe()` and in the refusal message
(`"(assumed; kore was not told)"`).

**That is not this item being decided.** It is the hypothesis running so the rest of the library
could be built, and the alternatives it names are still on the table — refusing to start without one,
or skipping the fit check when the period is unknown. What the implementation does settle is the
half that was never in doubt: the assumption is visible rather than silent.

Changing the answer is now a small change in one constructor, which is the right size for a decision
that belongs to somebody else.
