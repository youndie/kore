---
id: B-50
title: "The sample does not use the configuration schema"
status: open
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

- AC: the sample declares a schema with a required field, a default, a secret and a name that is a
  near miss of a real one; it refuses to start on an unknown variable in its prefix; `--print-config`
  prints and exits.
- AC: the control arm (`--kore=false`) still starts and still serves, so the measurement keeps two
  arms.
- AC: the three numbers are re-measured on the changed binary and both published tables are replaced
  in the same change.
- Anchors: `samples/service/`, `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/`
