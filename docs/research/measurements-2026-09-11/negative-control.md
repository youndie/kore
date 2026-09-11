# The negative control — [B-03](../../backlog/B-03-negative-control.md), 2026-09-11

The run [research-oracle](../research-oracle.md) §1 asks for **before kore exists**: the oracle
against a service built the ordinary way, to find out whether the premise of the library is real.

Linux build box, `samples/oracle/negative-control.sh 3`. Closed loop, 8 keep-alive connections on
`/work?ms=3000`, `SIGTERM` to PID 1. Three repetitions per cell, because one run per variant is not
a measurement. Every cell was identical across its three runs.

## The matrix

| Platform | Cell | A1 in-flight finished | A2 no 500 | Exit after signal |
|---|---|---|---|---|
| jvm | Ktor's default grace (1000 ms) | **FAIL** ×3 | PASS | ~1.46 s |
| jvm | grace 20 s | PASS ×3 | PASS | ~20.5 s |
| jvm | grace 20 s **+ the stop subscriber closes a resource the request uses** | PASS ×3 | **PASS** ×3 | ~20.5 s |
| native | Ktor's default grace (1000 ms) | **FAIL** ×3 | PASS | ~1.53 s |
| native | grace 20 s | PASS ×3 | PASS | ~20.6 s |
| native | grace 20 s **+ the stop subscriber closes a resource the request uses** | PASS ×3 | **FAIL ×3 — 48 responses in 5xx** | ~20.55 s |

## The verdict on the premise: confirmed, in its corrected form

**Same source. Same configuration. Same load. On the JVM, closing a pool in `ApplicationStopping` is
safe; on Kotlin/Native it breaks 48 requests with 5xx, on every run.** That is research §1.1's
ordering difference, producing a real defect, in an experiment designed before the code.

It is worth being exact about what was confirmed, because an earlier version of §1.1 claimed more.
The danger is **not** that Ktor cancels an in-flight request on native — row 5 shows every in-flight
request completing there. The danger is that the *idiomatic subscriber* runs before the drain and
takes away what the still-running requests need. kore's release stage running after
`EmbeddedServer.stop` is what removes it.

## The second finding, which is simpler and hits everybody

Rows 1 and 4: **at Ktor's default grace period of 1000 ms, every request slower than a second is cut
off — on both platforms.** No ordering is involved. An unconfigured Ktor service drops in-flight work
on `SIGTERM`, full stop, and that alone justifies a library that sets these numbers from its own
stage deadlines.

It is also what made this experiment nearly fail. At the default, rows 1 and 4 look like a
confirmation of §1.1 and are not one: both platforms fail, for a reason that is not the ordering.
Only the long-grace rows separate the two effects.

## What twelve green runs did not say

The first version of this control had only the first two cells per platform, and its
`ApplicationStopping` subscriber closed nothing. Twelve runs, all consistent, and they demonstrated
**nothing at all** about §1.1 — a subscriber with no consequence has no consequence whichever side of
the drain it runs on. The third cell exists because the absence was noticed; it would have been very
easy to write the twelve up as "the ordering makes no difference in practice".

## Two ways this experiment silently measured nothing

Both happened, both were caught by looking at the output rather than the exit code, and both are the
same shape — a subject that was never configured the way the run believed.

1. **Stale images.** The binaries were rebuilt and the containers were not, so the `--grace` cell ran
   the previous build and produced four cells of results about one configuration.
2. **A separator.** `--subject-args=--grace=20000 --close-on-stop=true` passed through Gradle's
   `--args`, which splits on spaces — so `--close-on-stop=true` was parsed as an argument of the
   *oracle* and never reached the subject. The decisive cell came back green twice before that was
   found. It is comma-separated now, with the reason in the code.

## A note on A1 and A2

In row 6, A1 **passes** while A2 fails. That is correct and is why they are separate assertions: A1
asks whether a response arrived and was complete, and a `500` with a complete body is a response. A2
asks whether anything ran against something already closed. An oracle with only A1 would have called
row 6 a success.
