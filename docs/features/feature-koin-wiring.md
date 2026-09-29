---
id: feature-koin-wiring
title: Koin for a server, without a scope per call
type: feature
status: active
owner: unassigned
involved_services:
  - kore-library
client_entries: []
api: []
tags: [koin, di, memory, kotlin-native]
---

# Koin for a server, without a scope per call

> **Built** ([B-65](../backlog/B-65-koin-call-scope-leaks-on-native.md)). `installKoreKoin { … }` in
> `kore-koin`. kore still does not own DI — the README says so — and this module does not change
> that: it owns one leak in the way DI is usually wired, and nothing else.

## 1. Overview

Most of the portfolio's servers wire Koin with `install(Koin) { modules(…) }`. That plugin opens a
Koin scope for every call and closes it after the response. On Kotlin/Native a Koin scope owns a
lock whose Linux implementation is a native mutex that nothing ever frees, so each request leaves
about a hundred bytes of malloc behind, forever. On tracy that was 154 MB of a 176 MB resident set
after four days — more than the store, the allocator and the threads together.

`installKoreKoin` builds the same container without the per-call scope. Routes that use `get` and
`inject` do not change; a server that resolves per-request definitions from `call.scope` cannot use
it.

## 2. Business rules

1. **No scope per call.** A request through an application wired by `installKoreKoin` has no
   `KOIN_SCOPE_ATTRIBUTE_KEY` attribute. That attribute is what the leaking scope hangs on.
2. **Routes resolve as before.** `get`/`inject` on `Application` and `Route` read the application
   attribute `setKoin` writes, which is the same one the plugin writes.
3. **Not global.** The container is a `koinApplication`, not `startKoin`. Two applications in one
   process (a test suite) do not share or stop each other's container.
4. **Closed after the engine.** On `ApplicationStopped`, not `ApplicationStopping`: on Kotlin/Native
   the latter arrives while requests are still draining (research §1.1), and a definition's
   `onClose` that releases a pool would release it under them.
5. **Refuses half a migration.** With koin-ktor's plugin already installed the call fails with a
   message naming B-65 — the two together still leak, and the call would read as the fix.

## 3. What it gives up

`call.scope` and every `scope<RequestScope> { … }` definition. None of the portfolio's servers used
them when this was written; a server that does keeps the plugin, and the leak, until Koin or
Stately fixes it upstream (B-65, *Not done here*).

## 4. Code anchors

| Service | Code |
|---|---|
| kore-library | `kore-koin/src/commonMain/kotlin/io/github/youndie/kore/koin/KoreKoin.kt` — the one call |
| kore-library | `kore-koin/src/commonTest/kotlin/io/github/youndie/kore/koin/KoreKoinTest.kt` — the four scenarios below |

## 5. Scenarios (BDD)

All four are automated in common code and run on every target kore builds.

### Scenario: a call opens no Koin scope
* **Given:** an application wired with `installKoreKoin { modules(…) }`
* **When:** a route that injects from the container is called three times
* **Then:** each response carries the injected value, and no call has a Koin scope attribute
* **Automated:** `KoreKoinTest.routes resolve from the container and a call opens no scope`

### Scenario: the plugin it replaces does open one
* **Given:** the same route, wired with koin-ktor's `install(Koin)`
* **When:** it is called
* **Then:** the call has a Koin scope attribute — the control that makes the first scenario mean
  something
* **Automated:** `KoreKoinTest.control - koin-ktor's plugin opens a scope on every call`

### Scenario: installed next to the plugin it refuses
* **Given:** koin-ktor's plugin already installed
* **When:** `installKoreKoin` is called
* **Then:** it throws, and the message names B-65
* **Automated:** `KoreKoinTest.installed next to koin-ktor's plugin it refuses rather than half-fixing`

### Scenario: the container closes with the application
* **Given:** a single with an `onClose` callback, resolved once
* **When:** the application runs and then stops
* **Then:** the callback has not run while it runs, and has run after it stops
* **Automated:** `KoreKoinTest.the container is closed when the application stops`

## 6. Out of scope

- Per-request definitions (§3).
- Fixing Koin or Stately. The leak is theirs; this is a way around it.

## 7. Quirks

- **RSS cannot show this leak.** The Kotlin/Native heap swings by ±10 MB with each collection, and
  a 404 control moved RSS as much as the route under test. The leak shows as a count of 16 + 48-byte
  malloc chunks, not as a resident-set slope over minutes.
