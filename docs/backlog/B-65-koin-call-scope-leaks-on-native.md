---
id: B-65
title: "koin-ktor's per-call scope leaks a native mutex on every request on Linux"
status: done
priority: P1
size: S
stage: m6-release
epic: feature-koin-wiring
blocked_by: []
---

# B-65 — koin-ktor's per-call scope leaks a native mutex on every request on Linux

Found on a consumer, not here: tracy's stand pod (0.3.4) sat at 176 MB of anonymous memory after
four days under a 256 MiB limit, **after** every allocator recipe of the portfolio had been applied
(`fixedBlockPageSize = 16`, `MALLOC_ARENA_MAX = 2`, a pool of two). The same `install(Koin)` is in
five more native servers of the portfolio.

## What was read, not assumed

| Fact | Where verified |
|---|---|
| The plugin opens a `RequestScope` in `CallSetup` and closes it on `ResponseSent` | `koin-ktor` 4.2.2 `projects/ktor/koin-ktor/src/commonMain/kotlin/org/koin/ktor/plugin/KoinPlugin.kt:94-103` (tag `4.2.2`) |
| `Scope` is a `Lockable`; on native `Lockable` is `internal val lock = Lock()` and nothing closes it | `koin-core` 4.2.2 `.../core/scope/Scope.kt:54`, `.../nativeMain/kotlin/org/koin/mp/ThreadLocal.kt:6-7`; unchanged on Koin `main` 29.09.2026 |
| On Linux, stately's `Lock` is a cinterop `Arena` holding a `pthread_mutexattr_t` and a `pthread_mutex_t`; only `Lock.close()` → `internalClose()` → `arena.clear()` frees them | `touchlab/Stately` `stately-concurrency/src/linuxMain/kotlin/co/touchlab/stately/concurrency/Lock.kt` |
| On Apple the same `Lock` is an `NSRecursiveLock` and `close()` is empty — no native memory | `stately-concurrency/src/appleMain/.../Lock.kt:24` |
| `get`/`inject` on `Application` and `Route` read `KOIN_ATTRIBUTE_KEY`, which `setKoin` writes | `koin-ktor` 4.2.2 `ext/ApplicationExt.kt:38-42`, `plugin/KoinPlugin.kt:76-78` |

## What was measured, and where

- **In the live pod** (tracy 0.3.4, stand): the glibc arenas walked through `/proc/1/mem` from a
  `kubectl debug --target` container — 154 MB of **live** malloc in ~2.9 million chunks, under 1 MB
  free (not fragmentation), 0.6 MB counted by SQLite's own `sqlite3Stat`, ~20 MB for the
  Kotlin/Native heap and stacks. The chunks come in pairs, 32 bytes `[0][1]…` and 64 bytes
  `[ptr → that 32-byte chunk][0][0][1]…` — an `Arena` chunk starts with a pointer to the previous one,
  and `1` is `PTHREAD_MUTEX_RECURSIVE`.
- **The allocation stack** (gdb on the same image, WSL): `malloc(16)` and `malloc(48)` from
  `ArenaBase.alloc` ← `co.touchlab.stately.concurrency.Lock.<init>` ← `org.koin.mp.Lockable.<init>` ←
  `org.koin.core.scope.Scope.<init>` ← `org.koin.ktor.plugin.setupKoinScope`.
- **Per request** (same image, pairs counted in the process): fresh 30; 30 s idle +0; 5000 ×
  `/health/ready` +11 977; 5000 × a 404 +5 543. **RSS could not show this**: the Kotlin/Native heap
  swings ±10 MB with each collection, and a 404 control moved RSS as much as the probe route did.
- **After** (tracy without the plugin, same harness, youndie/tracy#70): 23 pairs before and after
  10 000 requests — the locks of the container's own instance factories, made once at startup.

## Done when

- [x] `kore-koin` with `installKoreKoin { … }`: `koinApplication` + `setKoin`, closed on
  `ApplicationStopped`, refusing next to the plugin — `KoreKoinTest`, with the plugin as the
  control for the scope check.
- [x] [feature-koin-wiring](../features/feature-koin-wiring.md), README and
  [kore-library](../services/kore-library.md) say what it is for and what it gives up.
- [ ] The portfolio's native servers moved to it — tracked in their own repositories.

## Not done here

- **Upstream.** The fix belongs in Koin (close the lock in `Scope.close()` on native) or in Stately
  (a cleaner on the `Arena`). Not filed from here without the owner's word.
- **`call.scope`.** A consumer that resolves per-request definitions cannot use this module and
  keeps leaking until upstream moves.
