---
id: B-63
title: "A SIGTERM in the first moments of serving segfaults the native sample"
status: open
priority: P2
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-63 — A SIGTERM in the first moments of serving segfaults the native sample

Found while measuring B-62: one restart run of the native sample was signalled the moment `/health`
first answered, and it died with `Segmentation fault`, exit 139.

## Measured

On the build box, the sample's native debug binary, `--kore=true`, a fresh port per start. Each start
was signalled as soon as `/health` answered, polled every 10 ms. The two binaries were alternated:

| binary | starts | exit 0 | exit 139 |
|---|---|---|---|
| `main` @ `8aae43a` (before B-62) | 200 | 199 | **1** |
| B-62 branch | 200 | 198 | **2** |

So it is **not** B-62's: `main` does it too, and one against two in 200 decides nothing between them.
Signalled half a second or more after serving, 40 of 40 stops were clean. Every crash log is the same
four lines — the build line, the configuration line, `Application started`, `Responding at` — and
then nothing. There is no shutdown transcript, so the crash came before kore's sequence printed
anything.

## Hypothesis, not established

There is a window between `EmbeddedServer.start` and kore's watch being installed inside
`runUntilSignal`. In it, the server already answers `/health`, and the handler armed for `SIGTERM` is
**Ktor's**. `start` installs it on native (research §1.3), and it runs `runBlocking` and `stop()` on
the signal stack — consequence 2 of that section, the thing kore's own handler exists to avoid. A
signal inside that window would run exactly that code.

To establish it rather than infer it: a core under `gdb` (the box's `core_pattern` pipes cores to
WSL's crash capture, so run the binary under `gdb` directly), or a build whose watch is installed
before `start`. If the crashes go to zero there, the window is the cause.

## Why it matters, and why it is small

A pod that is stopped in the first fraction of a second of serving — a rollout that changes its mind,
a failing startup probe racing a stop — crashes instead of stopping. The sequence would have had
nothing to drain, so what is lost is the clean exit, not a request. Kubernetes reports it as a crash
all the same.

## Directions, to be decided in this item

- Install kore's watch **before** `start`. On native this would be replaced by Ktor's, which `start`
  installs later, so it would need re-installing after `start` as well. The window then belongs to
  kore's handler, which only sets a flag.
- Block `SIGTERM` and `SIGINT` around `start` and unblock them once kore's handler is in place, so a
  signal in the window stays pending and is delivered to kore's handler. Threads created by `start`
  inherit the mask, so this needs care.
- `startForKore()` (B-60) is where either would live, since it already owns "starting the way kore
  needs".

- AC: 400 early-signalled starts of the native sample, with no exit 139, and each one ends with a
  shutdown transcript. The cause is established by one of the two checks above, not inferred.
- Anchors: `kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/StartForKore.kt`,
  `kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/RunUntilSignal.kt`
