---
id: B-03
title: "The negative control: run the oracle against the unfixed shape"
status: done
priority: P0
size: M
stage: m1-oracle
epic: feature-ordered-shutdown
blocked_by: [B-05, B-06]
---

# B-03 — The negative control: run the oracle against the unfixed shape

Before kore exists, run the oracle of [research-oracle](../research/research-oracle.md) §2 against a
service wired the ordinary way — one `ApplicationStopping` subscriber, one `/health`,
`embeddedServer(...).start(wait = true)` — on both targets.

- **The decision and its reason.** Every fact in research §1.1 predicts this breaks on Kotlin/Native
  and survives on the JVM. If it does not break, one of three things is true: the fact is wrong, the
  load is not load, or the harness cannot see the failure. Each is cheaper to learn in week one than
  after five features rest on the premise.
- **Rejected:** taking the source reading as sufficient. A reproducible symptom is not a mechanism,
  and a mechanism read in source is not a symptom; the library's whole argument needs both.
- Does **not** cover: fixing anything. The output is a measurement and, if it contradicts research
  §1.1, a correction written *at the point of divergence* in that document.

- AC: a run of the oracle against the unfixed sample on `jvm` and `linuxX64`, with the result written
  into `docs/research/measurements-<date>/`; research §1.1 either stands unchanged or carries a
  correction naming this run.
- Anchors: `samples/`, `docs/research/research-architecture.md`

## Iteration 1 — 2026-09-11

**Run. The premise holds, in its corrected form** —
[the matrix](../research/measurements-2026-09-11/negative-control.md), 18 runs, every cell identical
across its three repetitions.

Same source, same configuration, same load. With a stop subscriber that closes a resource the
in-flight request uses — the ordinary thing a service does in `ApplicationStopping` — **the JVM is
fine and Kotlin/Native returns 48 responses in 5xx, on every run.** Research §1.1 now carries that
above its reasoning: the ordering is not a curiosity read out of two source files, it is a defect an
experiment reproduces on demand.

**The second finding is simpler and hits everybody.** At Ktor's default grace period of **1000 ms**
both platforms drop in-flight work, for a reason that has nothing to do with ordering. An
unconfigured Ktor service loses requests on `SIGTERM`, full stop.

**Three ways this nearly produced a confident wrong answer, all recorded rather than tidied away:**

1. **The first control had no consequence.** Its `ApplicationStopping` subscriber closed nothing, so
   twelve consistent runs demonstrated nothing at all about §1.1 — a subscriber with no effect has no
   effect whichever side of the drain it runs on. They would have read as "the ordering makes no
   difference in practice". The third cell exists because the absence was noticed.
2. **Stale images.** The binaries were rebuilt and the containers were not, so a whole cell ran the
   previous build and looked consistent doing it.
3. **A separator.** `--subject-args=--grace=20000 --close-on-stop=true` through Gradle's `--args`,
   which splits on spaces — so the second argument was parsed by the *oracle* and never reached the
   subject. The decisive cell came back green twice before that was found. Comma-separated now, with
   the reason in the code.

All three are the same shape: a subject that was never configured the way the run believed, found by
reading the output rather than the exit code. Two of them are now rules in `CLAUDE.md`.

**Also here:** the sample takes `--port`, `--grace` and `--close-on-stop` as **arguments** rather than
environment variables — reading the environment on Kotlin/Native needs the `expect`/`actual` pair
that [B-21](B-21-config-schema.md) will build, and `main(args)` needs nothing. Absent `--grace`, the
server keeps Ktor's default, which is what makes it the control.

**A note on the assertions:** in the decisive cell A1 **passes** while A2 fails, and that is correct.
A1 asks whether a response arrived complete — a `500` with a body is a response. A2 asks whether
anything ran against something already closed. An oracle with only A1 would have called that cell a
success.
