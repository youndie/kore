---
id: B-50
title: "The sample does not use the configuration schema"
status: done
priority: P2
size: M
stage: m4-config
epic: feature-typed-config
blocked_by: []
---

# B-50 — The sample does not use the configuration schema

`samples/service` reads its port from a constant, under a comment saying it will read one properly
*"until there is a configuration schema to read one with — that is B-21"*. B-21 is `done`: the schema
DSL, the reader, `--print-config` and the unknown-variable refusal all exist and are covered by
`kore-core`'s suites.

So the one feature of the five that a running binary never exercises is the configuration. That is
the gap a sample exists to close: the tests assert the schema behaves, and only a binary asserts that
a service *can be written against it* — the shape of an API is a different claim from the behaviour
of its parts, and only the second is under test.

Found through the anchors report, which had been naming the missing file for as long as the row
existed — see [feature-typed-config](../features/feature-typed-config.md) §5.

## What it costs, and why it is not free

`samples/service` is the **measurement subject**: the three numbers in the README and in
[three-numbers.md](../research/measurements-2026-09-12/three-numbers.md) were taken from this binary
with `--kore=true` against `--kore=false`. Adding a schema to the kore arm moves RSS and startup by
some amount, and a number that was measured on a different binary is a number about a different
binary.

So this item is **not** "wire four fields in"; it is that plus re-running
`:samples:oracle:measure` and replacing both tables. Sized `M` for that reason, and worth doing as
one change rather than two — a sample edit that silently invalidates a published table is exactly
the drift the README section was written to resist.

**Rejected: deleting the claim and leaving the sample alone.** Done, as the immediate fix — the
document no longer says something untrue. But it leaves the asymmetry: four features demonstrated in
a binary, one demonstrated only in unit tests, with nothing saying which.

## Done — 2026-09-12

`SampleConfig` declares five keys and **every one of them is read by something**, which was the rule
rather than a nicety: a schema whose values nothing consumes would have satisfied the document and
demonstrated exactly what the document already falsely claimed.

`samples/oracle`'s `configRefusal` runs six cases against the built image, all passing:

| Case | Asserted |
|---|---|
| configured — **the positive control** | the container is still running |
| the required key is missing | exit 1, naming `SAMPLE_POOL_DSN` |
| a near miss under the prefix | exit 1, `did you mean SAMPLE_WORK_MS?` |
| half of the observability pair | exit 1, `must be set together with SAMPLE_TRACY_ENDPOINT` |
| `--print-config`, usable | exit 0, the secret rendered `••••••` |
| `--print-config`, unusable | exit 1, and it prints what it *did* resolve |

The positive control is what stops the other five being passed by a binary that refuses everything.

### The numbers moved, and one of them stopped being a number

Re-measured at **n=15** and both tables replaced
([write-up](../research/measurements-2026-09-12/three-numbers.md),
[raw](../research/measurements-2026-09-12/raw-three-numbers-with-config.txt)).

n went from 5 to 15 because the first re-run **disagreed with itself**: two runs of five put native
RSS at −320 kB and at +2 400 kB. A difference whose sign depends on which five runs you took is not a
difference.

At fifteen, the startup cells come out **negative on both platforms** — kore, doing strictly more
work than before, appearing to start sooner — against a within-arm spread of 145 ms on the JVM and
nearly a second on native. So startup is published as **noise**, and the previous table's
**+12 ms (+1 %)** / **+5 ms (+1 %)** is corrected: those were the same noise with the other sign,
printed as a small cost. A figure small enough to be noise should be published as noise; the plus
sign is what a reader remembers.

What survives is RSS — **+6.4 MB on the JVM, +1.3 MB on native** — and the stop column, which is not
a cost: the control still finishes **none** of the requests in flight at the signal and drops all of
them, in all fifteen rounds, on both platforms.

- AC: the sample declares a schema with a required field, a default, a secret and a name that is a
  near miss of a real one; it refuses to start on an unknown variable in its prefix; `--print-config`
  prints and exits. **Met**, and asserted against the image rather than in a unit test.
- AC: the control arm (`--kore=false`) still starts and still serves, so the measurement keeps two
  arms. **Met** — it takes no environment at all, which is the comparison.
- AC: the three numbers are re-measured on the changed binary and both published tables are replaced
  in the same change. **Met.**
- Anchors: `samples/service/`, `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/`
