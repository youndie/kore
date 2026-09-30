---
id: B-66
title: "Take builtAt from SOURCE_DATE_EPOCH, so one commit links to one binary"
status: done
priority: P2
size: XS
stage: m5-wiring
---

# B-66 — Take builtAt from SOURCE_DATE_EPOCH, so one commit links to one binary

[#102](https://github.com/youndie/kore/issues/102). A clean build of a service applying `kore-build`
never links to the same bytes twice: `builtAt` is the wall clock, and `builtAtFor` keeps a previous
value only when a previous file exists — never in CI or in an image build. Measured on keel at one
commit: two clean links 26 bytes apart, 6 of them this string and 20 the build-id that hashes the
output; with the same identity file in both, byte-identical.

- **`sourceDateEpoch`, read from `SOURCE_DATE_EPOCH` by default.** Set, it names `builtAt`, whatever
  the previous file said. Unset, nothing changes — a new input with today's behaviour as the default,
  as #102 proposed.
- **An environment variable by default, unlike `commit`.** Which variable names a commit is the
  consumer's fact; `SOURCE_DATE_EPOCH` is the reproducible-builds standard, and every toolchain that
  honours one reads that name.
- **Anything but whole seconds fails the task** rather than being ignored: a date string silently
  falling back to the wall clock is the non-reproducibility this exists to remove, with no sign.
- Rejected: defaulting `builtAt` to the commit time of a clean tree. It changes what `built:` means for
  every consumer; an opt-in does not.

- AC: `builtAtFor` returns the given second whatever the previous file held; a blank value is no value;
  a non-numeric value fails naming the variable — `GitFactsTest`, three new tests, 21 of 21 green.
- Anchors: `kore-build/src/main/kotlin/io/github/youndie/kore/gradle/BuildIdentityPlugin.kt`,
  `kore-build/src/test/kotlin/io/github/youndie/kore/gradle/GitFactsTest.kt`

**Not run by CI, and that is B-67**: `kore-build` is an included build, and the check workflow runs
neither its tests nor its plugin validation. These tests ran locally (`./gradlew :kore-build:test`).
