# The treatment arm — [B-39](../../backlog/B-39-kore-wired-sample.md), 2026-09-12

The other half of [the negative control](negative-control.md): the same sample wired **with** kore,
run by the same oracle, against the same scenario. `samples/oracle/negative-control.sh 3` — 24 runs,
every cell identical across its three repetitions.

| Platform | Cell | A1 in-flight | A2 no 500 | Exit after signal |
|---|---|---|---|---|
| jvm | Ktor's default grace | **FAIL** ×3 | PASS | ~1.5 s |
| jvm | grace 20 s | PASS ×3 | PASS | ~20.5 s |
| jvm | grace 20 s + closes a pool in `ApplicationStopping` | PASS ×3 | PASS ×3 | ~20.5 s |
| jvm | **kore** | PASS ×3 | PASS ×3 | ~15.5 s |
| native | Ktor's default grace | **FAIL** ×3 | PASS | ~1.5 s |
| native | grace 20 s | PASS ×3 | PASS | ~20.5 s |
| native | grace 20 s + closes a pool in `ApplicationStopping` | PASS ×3 | **FAIL ×3 — 48 in 5xx** | ~20.5 s |
| native | **kore** | PASS ×3 | **PASS ×3** | ~17.5 s |

## The claim, demonstrated

Rows 7 and 8. **The same service, closing the same resource, on the same signal** — and the only
difference is *when* the close happens. In `ApplicationStopping` it runs before the drain on
Kotlin/Native and breaks 48 requests. In kore's release stage it runs after, and breaks none.

That is research §1.1 stated as a before and an after rather than as a reading of two source files.

## The trap this nearly fell into, again

The first version of the treatment arm **closed nothing**. It passed 9 of 9 on both platforms and
proved nothing: an arm that does not do the thing cannot demonstrate that doing it at the right time
is safe. It would have been comparing a service that closes a resource against one that does not.

This is the third time in this repository that an experiment silently measured nothing, and the
second time the cause was an arm with no consequence — the negative control had the same defect
before its third cell existed. The pattern is worth naming: **when a comparison passes, check that
both arms actually do the thing.**

## The full oracle, with nothing not-applicable

The kore runs are the first to exercise every assertion:

```
PASS  A1 in-flight requests finished — 8 spanned the signal, all completed
PASS  A2 no 500 — every answered request was a normal status or 503
PASS  A3 503 carries Connection: close — 8 refusals, all carrying it
PASS  A4 readiness fell before the first refusal — by 2929ms
PASS  A5 the pre-drain wait was honoured — 2929ms >= 2000ms
PASS  A6 exited itself inside the grace period — exit 0 after 17490ms of 30000ms
result: 9 passed, 0 failed, 0 inconclusive, 0 not applicable
```

Against the control, A3, A4 and A5 report `NOT_APPLICABLE`: it refuses nothing and has no readiness
endpoint. **Zero not-applicable is the number that says the instrument finally has a subject for
every question it asks.**

**A7 — the two platforms agree — is satisfied for the first time**: both kore runs are 9 of 9, with
the same verdicts in the same order.

## What the exit times are not

kore's rows are faster (15.5 s and 17.5 s against 20.5 s) and **that is not a result**. The two arms
have different budgets: the probe cells use a 20-second engine grace, kore uses its own 15-second
drain deadline. Comparing them would be comparing two configurations, not two designs. The honest
reading is only that kore stayed inside the grace period it was given, which is A6.
