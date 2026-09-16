---
id: B-57
title: "A4 compares an instant against a poll, and fails any fast route"
status: done
priority: P0
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-57 — A4 compares an instant against a poll, and fails any fast route

Reported from a consumer as [#83](https://github.com/youndie/kore/issues/83), and **opened by
[B-54](B-54-oracle-drives-any-path.md)**: `--path` let the oracle be pointed at a service whose route
answers in about a millisecond, and A4 then failed it three runs out of three.

`readinessFellAtNanos` is the first sample that was **not** `200`. The poller samples every 100 ms —
its own comment says so and says why — so the flag flipped somewhere in `(last 200, first non-200]`,
while a refusal is a real exchange with a real timestamp. With a fast route a driver issues its next
request microseconds after the signal and is refused at once, so the refusal precedes the *sample*
essentially always. Deterministically, not flakily, which is what three identical runs showed.

**It was unreachable while `samples/service` was the only subject.** `/work?ms=3000` keeps every
driver busy for three seconds after the signal and the poller has thirty samples of margin. An
assertion's precision assumption held for as long as one subject was slow.

## Three answers, because the instrument gives three

| Where the first refusal lands | Verdict |
|---|---|
| before the **last** observed `200` | **FAIL** — no sampling error reaches back that far |
| after the **first** non-`200` | **PASS** |
| between them | **NOT_APPLICABLE**, naming the width |

The reporter offered "pass on the upper bound" or "inconclusive inside the interval". Neither alone:
passing the ambiguous band would pass a real violation smaller than a poll, and the band is not a
failed *run* — it is one assertion this subject's shape puts out of reach, which is what
`NOT_APPLICABLE` already means here and what keeps the run from exiting non-zero for a reason that is
not behaviour.

**Tightening the poll does not fix it** and was not done. The race is against a route that answers in
a millisecond; no interval beats that, and a faster poll during the drain is load the measurement does
not need.

## Seen all three ways

| | |
|---|---|
| `/work?ms=3000`, 8 connections | **PASS** by 2944 ms — the run this harness has always made |
| `/work?ms=1`, 32 connections | **NOT_APPLICABLE** across a 112 ms interval, 3325 refusals — the consumer's case, on kore's own sample |
| the FAIL branch | `A4BracketTest`, because a correct subject cannot produce one |

`/version` does **not** reproduce it: it is in `KoreRoutes.servedWhileShuttingDown`, so a shutting-down
pod still answers it and nothing is refused. The reproduction needs a route that is both fast **and**
refusable.

## The assertions had no tests at all

`evaluate()` reads `Observations` and nothing else, so a case is a constructed record — no container,
no Docker, and it runs inside `check`. That it took a consumer to find an assertion which failed every
fast subject is the argument for the suite, and the suite is three cases because the assertion now has
three answers.

- AC: A4 reports a violation, a pass, and its own inability to tell, each demonstrated. **Met.**
- Anchors: `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Assertions.kt`,
  `samples/oracle/src/test/kotlin/io/github/youndie/kore/oracle/A4BracketTest.kt`
