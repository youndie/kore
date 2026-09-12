---
id: B-48
title: "The publish workflow names three modules and the build has four"
status: done
priority: P1
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-48 — The publish workflow names three modules and the build has four

`kore-booblik` is in the build, in the documentation and in `0.1.0`'s feature set. It is **not in
any published coordinate**:

| Coordinate at `0.1.0` on the portfolio repository | |
|---|---|
| `kore-core`, `kore-ktor`, `kore-observability` | `200` |
| `kore-booblik`, and every per-target variant of it | `404` |

The publish workflow landed in [#40](https://github.com/youndie/kore/pull/40) naming three modules;
`kore-booblik` arrived in [#43](https://github.com/youndie/kore/pull/43) and the list never grew. The
comment above it still reads *"the three library modules"*, which is what a hand-written list does:
it stays right about the day it was written.

**This is not cosmetic.** [konekt#35](https://github.com/youndie/konekt/issues/35) proposes adopting
kore `0.1.0` to the first consumer, and the finding that issue answers —
[konekt#31](https://github.com/youndie/konekt/issues/31), a broker producer closed without a flush —
is fixed by exactly the module that cannot be resolved. The recommendation was made against a set
that does not exist.

## The fix is to delete the list, not to correct it

`maven-publish` is applied to every `:kore-*` module by one rule in the root build, so the root
`publishAllPublicationsToWipRepository` already means *all of them*. Naming them below it adds
nothing but a second place to be wrong.

The read-back is a second hand-written list with the same defect — five coordinates out of the
fifteen a four-module KMP build produces — so it stops being written too: the same publication goes
to a directory inside `build/`, and the check walks what was actually produced. A coordinate that
was never published is then a coordinate the loop never looks up, which is the failure it is there
to catch, so the walk asserts a floor as well.

**Rejected: a checker comparing `settings.gradle.kts` against the workflow text.** It would have
caught this instance and it guards the shape rather than the rule — the next module added with a
different publication layout passes it. A list that cannot drift beats a check on a list that can.

## Done — 2026-09-12

Both lists are gone. The publish step is the root task, and the read-back walks
`build/published` — the same publication written to a directory — so what is verified is what was
produced. `0.1.0-b48-probe` published locally produced **19 coordinates**, `kore-booblik` among them.

The one list that remains is the build's own: the completeness check reads `include(":kore-*")` out
of `settings.gradle.kts` and requires a root coordinate for each. **Seen red before being believed** —
with `kore-booblik`'s root coordinate moved aside, 18 of the 19 still verify and the check fails by
name. A floor on the count would have passed that, which is why there is no floor.

- AC: publishing at a fresh version puts every `:kore-*` module on the repository, and the job fails
  if any coordinate it produced is not served back. **Met**, and the failure is checked rather than
  assumed.
- AC: no module name appears in `.github/workflows/publish.yaml`. **Met.**
## What the first real publish found — 2026-09-12

This workflow had **never run**: `0.1.0` was published by hand from the build host, so the read-back
above went into its first execution untested. Two things came out of it.

**The token is scoped to artifact paths, and `kore-booblik` was not among them.** Reposilite matches a
route with `toPath.startsWith(route.path)` and a required trailing slash, so `…/kore-core/` does not
cover `…/kore-core-jvm/` — each per-target coordinate is its own route. The token was issued for the
15 coordinates of three modules; the fourth module's four were refused with `403`. The comment above
the read-back step had predicted exactly this and it still took a failed publish to notice, because
nothing checked it. Re-issued through `vedutsya-raboty/infra`'s `reposilite-token` workflow with all
**19** coordinates, and the list was derived from `build/published` rather than typed.

**A failed publish poisons its own version number.** The `403` stopped the run after three jars were
already up, and `snapshots` refuses an overwrite — so the second attempt failed with three `409`s
that said nothing about the `403` that caused them, and `0.1.1` can never be completed under that
number. It is now debris: four `-jvm` jars and no root coordinate, so nothing resolves it.

A pre-flight step now produces the publication locally, checks every artefact it is about to write,
and **refuses a version that already has any of them** — before the first `PUT`. Seen both ways
against the live repository: `0.1.1` is refused by name, `0.1.2` reports free.

- **Left open on purpose:** `0.1.0` on the repository stays incomplete. Re-publishing a released
  version to fix it would make the same coordinate mean two things; the fix ships as the next
  version, and [konekt#35](https://github.com/youndie/konekt/issues/35) is told which one to take.
- Anchors: `.github/workflows/publish.yaml`, `build.gradle.kts`
