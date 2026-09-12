---
id: B-30
title: "Decide whether kore owns the entry point"
status: done
priority: P2
size: S
stage: m5-wiring
blocked_by: [B-33]
---

# B-30 — Decide whether kore owns the entry point

Open question 2 of [research-architecture](../research/research-architecture.md) §3. Everything is
currently written as "kore mounts routes into your application and wraps your `EmbeddedServer`". The
alternative is that kore *is* the entry point: one call builds the server, installs the routes, reads
the configuration and runs the sequence.

- **The hypothesis.** Both, with the wrapper as the supported path and the pieces public. That is
  closer to the brief's "one line" without making the pieces unavailable to a service that has its own
  reasons.
- **Why it cannot be settled on paper.** A single sample always agrees with the API it was written
  against. The disagreement that decides this is a *second* consumer, which is why this item sits
  behind adoption rather than before it.
- **Rejected:** deciding now to avoid rework. The rework is one file; the wrong answer is an API.

## Decided 2026-09-12: kore does not own the entry point

**The wrapper stays the supported path and the pieces stay public.** kore mounts routes into an
application it is handed and wraps an `EmbeddedServer` it is handed; it does not build either.

### The evidence, and it is not the sample

A sample always agrees with the API it was written against — the item says so, and that is why it sat
behind adoption. What is readable without adoption is the **shape of the one real service kore
targets**, and that shape settles it:

| What konekt's `main()` does | What an entry point owned by kore would have to grow |
|---|---|
| reads its config, then **runs migrations and exits without serving** (`migrateOnly`) | a mode where kore starts nothing, which is an entry point that is not one |
| `embeddedServer(CIO, port, host) { module(config) }` | a way to choose engine, port and host — and to pass a config object kore knows nothing about |
| `install(Koin) { modules(...) }` inside the module, before the routes | an ordering hook for somebody else's DI container |

Each of those is a step towards **"not DI, not a router, not a config framework with seven sources"**
— the non-goals in the brief, in that order. An entry point that accommodates all three is a
framework, and the one thing kore promises is an *ordering*, which does not require owning `main`.

### What the decision costs, stated rather than hidden

The sample's `startKoreSample` is the honest price: **about eight lines of ceremony** around the
registrations — `start(wait = false)`, mark started, install the watch, `runBlocking`, await, run the
sequence, print the transcript, release the process. The registrations themselves are the service's
own content and no API can remove them.

Eight lines is not "one line", and the brief asked for one. The answer is not to own `main` but to
collapse the part kore *does* own — signal to sequence to release — into one call, which is
[B-46](B-46-run-until-signal.md).

### How strong this is, and what would reopen it

The item asked for a second consumer's **experience**; this is its **shape**. That is weaker: it
shows an entry point kore would have to fit around, not what it feels like to use kore's pieces in
anger. It is enough to stop the API growing on speculation, which is what an open question costs.

Reopen it if adoption ([konekt#35](https://github.com/youndie/konekt/issues/35)) finds the wiring
repetitive in a way B-46 does not fix, or if a second consumer's entry point turns out to be a bare
`main` with nothing of its own in it — in which case the balance of the table above changes.

- AC: a decision recorded here, with the second consumer's experience as the evidence;
  [services/kore-library](../services/kore-library.md) §2 updated to match.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/`
