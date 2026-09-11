---
id: B-22
title: "Environment enumeration per target, and the honest macOS gap"
status: done
priority: P0
size: M
stage: m4-config
epic: feature-typed-config
blocked_by: [B-21]
---

# B-22 — Environment enumeration per target, and the honest macOS gap

Lookup everywhere; enumeration where it exists.

- **The decision and its reason.** Research §1.5, all of it verified in the Kotlin/Native 2.4.10
  platform klibs: `platform.posix` exposes **`__environ`** on `linux_x64` and `linux_arm64` — not
  `environ` — and exposes neither on `macos_arm64`; `_NSGetEnviron` is not in `platform.posix`,
  `platform.darwin` or `platform.Foundation` either. So the unknown-variable check is a capability of
  the JVM and the Linux native targets, and on macOS native it is **unavailable**.
- **Rejected, and this is the whole point of the item:** returning "no unknown variables found" on
  macOS. A check that always passes is worse than an absent one, because a deployment reads it as
  evidence. `--print-config` states the capability of the target it is running on.
- **Rejected:** a cinterop `.def` for `_NSGetEnviron` to close the gap. It is buildable, and it would
  buy the enumeration on a target nothing is deployed to, at the price of a native interop step in the
  core module. Revisit only if macOS native stops being a development convenience.

- AC: enumeration works on `jvm`, `linuxX64` and `linuxArm64`; on `macosArm64` the capability reports
  itself absent and a test asserts that it does.
- Anchors: `kore-core/src/linuxMain/`, `kore-core/src/macosMain/`, `kore-core/src/jvmMain/`

## Iteration 1 — 2026-09-12

**Done.** `EnvironmentNames`, `systemEnvironment()` for all four targets. 7 tests; 68 green on
`linuxX64`, 64 on `jvm` (the difference is the Linux-only walk tests).

**"Cannot list" and "listed nothing" are different shapes**, and the compiler makes a caller handle
both. A nullable set or an empty one would let a deployment on macOS read "no unknown variables
found" as evidence — a check that always passes is worse than an absent one, which is the rule this
repository keeps re-learning.

**`KorePlatform.canEnumerateEnvironment` is now derived rather than declared.** Each target used to
state it as a constant beside the implementation that had to match it: two sources of truth for one
fact, and the day they disagree is the day a deployment is told it has a capability it does not.

**Two of the tests did not earn their place, and a mutation said so.** "PATH is among the names"
catches a walk that drops its first entry only if PATH *happens* to be first; "every listed name
resolves" catches a split on the wrong `=` only if the process *happens* to hold a value containing
one. Both passed against a deliberately broken walk. The replacement is a **second implementation**,
not a better guess: `/proc/self/environ` is the kernel's own enumeration, reached by a different
mechanism, and comparing the two sets kills both mutations.

| Mutation | Before the kernel test | After |
|---|---|---|
| split `NAME=value` on the last `=` | **survived** | red |
| drop the first entry of the walk | **survived** | red |
| macOS reports an empty list instead of unavailable | **cannot be caught** — see below | |

## Two process failures, both worth more than the code

**The mutation-revert trap caught this repository a second time, in a new way.** The production code
*was* committed before mutating — the rule from B-11 was followed. Then the kernel-comparison test
was written, and not committed, and the next `git checkout -- kore-core/src` deleted it. The mutation
after that was judged by four old assertions and "survived". Committing the change is not enough;
commit whatever was written since. `CLAUDE.md` now says that.

**And a 621 ms "BUILD SUCCESSFUL" was the Gradle build cache**, not the suite: `linuxX64Test
UP-TO-DATE`. A deleted test, a test that never compiled in, and a cached result are indistinguishable
from a surviving mutant — all three are a green build. The rule added: after a mutation, read the
result file for the test's *name*.

## The gap that stays

The macOS branch **compiles** on a Linux host and its tests do not run there, so nothing in CI
exercises it — a mutation making it report an empty list cannot be caught. That is the same shape as
the portfolio's "a green build means the Apple code compiles", and it is stated in
[kore-library](../services/kore-library.md) §2a rather than left for somebody to assume otherwise.
The target is a development convenience; the servers run on Linux.
