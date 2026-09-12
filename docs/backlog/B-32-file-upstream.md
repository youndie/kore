---
id: B-32
title: "File the upstream proposals that are ready; ask before Ktor"
status: question
priority: P2
size: S
stage: m6-release
blocked_by: [B-03]
---

# B-32 — File the upstream proposals that are ready; ask before Ktor

[research-upstream-proposals](../research/research-upstream-proposals.md) holds five entries and none
is filed.

- **The decision and its reason.** Two different permissions apply. `youndie/*` — booblik, tracy,
  metrik, katcher — is the working arrangement and needs none: §2, §3, §4 and §5 can be filed as
  written. **Ktor is somebody else's tracker and is asked about first**; an issue costs the maintainer
  time and cannot be quietly withdrawn.
- **And the Ktor entries wait for evidence anyway.** "The order differs" is a reading of the source;
  "a request is dropped because of it" is a result. [B-03](B-03-negative-control.md) is what turns the
  first into the second, and a claim filed without it is a claim the reporter cannot defend.
- Does **not** cover: closing anything. An entry leaves the proposals document when the fix is read in
  the source **and** in the published artefact of the version kore resolves — "closed" and "fixed" are
  different claims and only the second is checkable.

- AC: four issues filed in `youndie/*` with the verification addresses — **done**; the Ktor entries
  either asked about and filed, or recorded as deliberately not filed with the reason — **this is the
  question below**.

## Filed 2026-09-12

Each claim was re-read against the current source of its own repository before being filed, not
trusted from this document — and that caught two things (below).

| Entry | Issue | Re-verified against |
|---|---|---|
| §2 metrik | [youndie/metrik#29](https://github.com/youndie/metrik/issues/29) | metrik `9b8af23` — `stop()` still cancels and closes without flushing; the plugin still subscribes `agent.stop()` to `ApplicationStopping` at `Metrik.kt:89` |
| §3 tracy | [youndie/tracy#32](https://github.com/youndie/tracy/issues/32) | tracy HEAD — `stop(grace)` exists and is a bounded final flush; nothing calls it by default |
| §4 booblik | [youndie/booblik#68](https://github.com/youndie/booblik/issues/68) | booblik `08c9bae` — JVM `drainPending()` fails the accumulated batches; native begins with `sendAll()` |
| §5 katcher | [youndie/katcher#50](https://github.com/youndie/katcher/issues/50) | katcher `b9b953f` — `start` is still the only lifecycle function |

Written in Russian, matching the prose of the repositories they were filed in.

## The question — the two Ktor entries

**Their evidence is now in.** [B-03](B-03-negative-control.md) turned §1.1 from a reading of the
source into a result: same source, same configuration, same load, and on Kotlin/Native the idiomatic
`ApplicationStopping` subscriber breaks **48 requests with 5xx on all three runs**, while the JVM
breaks none. That is a claim a reporter can defend.

**What is missing is permission, and it is not mine to assume.** Ktor is somebody else's tracker: the
issue costs a maintainer time, it cannot be quietly withdrawn, and it is a public statement about
their design made in this repository owner's name. So: file them, or not?

If yes, the shape they should take is already decided here — §1.1 as a **question** ("is this
difference deliberate?") with the measurement attached, not as a bug report; and §1.2 as *"the
callback should not run on the signal stack"* and explicitly **not** as "the slot should be a list",
because a list would make kore's position worse rather than better.

## Two things the re-reading caught

* **A stale claim about kore itself, one paragraph from being pasted into somebody else's tracker.**
  §1.2's "what kore does meanwhile" said kore installs its handler with `sigaction`. It does not, and
  has not since B-08 — the `sigaction` struct differs between Linux and Darwin, so kore uses ANSI
  `signal()` with a flag-only handler. Corrected in place.
* **A refuted fact in this repository's own research**, found because booblik's HEAD commit is
  literally *"docs(native): Dispatchers.IO is not internal on Kotlin/Native"* — the opposite of what
  §1.14 was written to say the day before. Verified here: it compiles and runs on `linuxX64` and
  `macosArm64` with `import kotlinx.coroutines.IO`. §1.14 is amended and
  [B-42](B-42-dispatchers-io-exists-on-native.md) owns what it does to D9.
- Anchors: `docs/research/research-upstream-proposals.md`
