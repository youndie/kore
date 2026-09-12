# CLAUDE.md — kore

One Kotlin Multiplatform library that owns the process lifecycle of a server binary: the ordered
shutdown, three probes, a typed configuration from the environment, one-call observability wiring,
and `/version`. **Native-first** — `linuxX64` and `linuxArm64` decide the design; `jvm` and
`macosArm64` follow.

**All five features are built.** Four modules carry source — `kore-core`, `kore-ktor`,
`kore-booblik`, `kore-observability` — plus `samples/service` and `samples/oracle`, and `0.1.0` is
published. A path under `kore-*/src/` or `samples/` named in a document is a path that exists unless
the document says otherwise.

This paragraph said *"almost no code yet, treat every path as where the code will live"* for a week
after that stopped being true, and the README's status line was wrong twice the same way. A sentence
about the state of the project has no way to fail; `backlog.md` and the build do. Prefer them.

## How to start a session

1. [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was read
   in the artefacts and what follows from it. Skipping it costs a day per finding, and five of its
   eleven facts contradict what a Ktor example would lead you to write:
   - **`EmbeddedServer.stop` runs its steps in the opposite order on JVM and on Kotlin/Native** (§1.1).
     `ApplicationStopping` fires after the drain on one and before it on the other. This is why the
     library exists;
   - **`ApplicationStopPreparing` fires after the socket has stopped accepting** on CIO (§1.2), so it
     is useless for flipping readiness — which is the one thing its name suggests;
   - **the Native shutdown hook is one global slot and runs on the signal stack** (§1.3), so kore
     never uses `addShutdownHook` and installs its own handler, which writes a flag and nothing else;
   - **`Connection: close` in a response does not close a CIO connection** (§1.4) — the engine reads
     the *request's* header. kore promises the header, not the socket, and the oracle asserts
     accordingly;
   - **enumerating the environment is `__environ` on Linux and does not exist on macOS native**
     (§1.5), so the unknown-variable check is a declared capability rather than a universal one.
2. [docs/research/research-oracle.md](docs/research/research-oracle.md) — the acceptance experiment
   and the property test, written before the code on purpose. Read it before implementing any stage:
   the properties are the specification, and §3.3 names in advance the mutations that must make the
   suite red.
3. [backlog.md](backlog.md) — the goal, the stages and the index. Items are one file each in
   `docs/backlog/`; the index between the markers is generated, so edit the item and run
   `python3 scripts/backlog_index.py`.
4. The layer document the task belongs to — [`docs/features/`](docs/features/),
   [`docs/api/`](docs/api/), [`docs/services/`](docs/services/). The map is
   [docs/README.md](docs/README.md).

## Where things build

This repository is a mutagen session (one-way replica, alpha here, beta `kore` on the Linux box).
**Gradle runs there**, through the wrapper:

```bash
~/.claude/bin/wsl-run ./gradlew build
```

**Edits, `git` and the documentation checks stay on the Mac**, and need `LOCAL=1` to get past the
hook:

```bash
LOCAL=1 make check
```

The replica is one-way: work done there is reverted, and a diff taken there proves nothing.

**What a green `build` covers, measured on 2026-09-11 rather than assumed:**

| On the Linux box | |
|---|---|
| `compileKotlinJvm` / `LinuxX64` / `LinuxArm64` / `MacosArm64` | **run** — all four produce artefacts, the Apple klib cross-compiles |
| `jvmTest`, `linuxX64Test` | **run** |
| `linuxArm64Test`, `macosArm64Test` | **SKIPPED**, inside `BUILD SUCCESSFUL` |

So green means the `linuxArm64` and `macosArm64` code *compiles*, and says nothing about a test
there. Anything that must hold on those targets needs a test that runs where they run — or it is not
covered, and the document says so.

**Read a result file, not a log line.** `BUILD SUCCESSFUL` through a pipe has been wrong in this
portfolio before; the test-result XML and the artefact's timestamp have not. CI does the same: the
build job's last step prints every result file with its counts and fails when there are none, because
a suite that ran zero tests exits zero.

**CI is two jobs — `check` and `build` — and neither covers the other.** `check` is `make check`, the
documentation gate. `build` is `./gradlew build` for all four targets. Until B-07 there was only the
first, which meant a pull request that did not compile was green; that is worth remembering the next
time a gate looks like it covers more than it does.

## The two rules

- **`main` describes what exists.** Every feature document is `status: draft` because the code does
  not exist. When a feature is built, its document becomes `active` **and is re-read against the
  code** — not flipped. `docs_check.py --on-main` is the mechanical half and it is off with an
  address: [B-35](docs/backlog/B-35-draft-gate.md), not a relaxed rule.
- **What was verified is separated from what was assumed, explicitly.** Everything in
  research §1 carries a file and a line. Everything else says "decision" or "hypothesis", and a
  hypothesis carries the milestone where it is settled. A document that blurs the two is a defect
  worth an item, because both halves then look equally authoritative.

## Rules that are cheap to follow and expensive to discover

- **Never put shutdown work in `ApplicationStopping`.** Research §1.1. On Kotlin/Native it runs
  before the drain. kore's release stage runs after `EmbeddedServer.stop` returns, and that is a
  specification rather than an implementation detail.
- **Never call `addShutdownHook`.** Research §1.3. One global slot on Native, last registration wins.
- **A signal handler sets a flag and wakes something. Nothing else.** Allocation, locks and
  `runBlocking` are not async-signal-safe, and Ktor's native hook does all three.
- **`__environ`, not `environ`**, on the Linux native targets — and neither exists on macOS native.
  Writing the POSIX-documented name gives an error naming a missing symbol rather than a missing
  platform.
- **A capability that is absent on a target says so.** Never return "nothing found" where the check
  could not run: a deployment reads that as evidence. This applies to the unknown-variable check
  today and to the profiler hook if it is ever built.
- **A check that has never run is not healthy.** The registry's initial state is "unknown" and
  readiness reports it as `503`.
- **A stale result is not a healthy result.** Past its refresh budget, a cached check answers `503`
  with the age of the last answer, not the last answer.
- **Flush before close, always — and the missing verb is *wait*, not *send*.** booblik's JVM
  `Producer.close()` does send the accumulated batch; it sends it on the producer's own coroutine and
  does not wait, so a shutdown that closes and then tears the scope and the connection down in the
  same breath loses it. Measured against a real broker in B-45: **1 of 51 records** survive that
  teardown, **51 of 51** when anything waits (research §1.8). This bullet used to say `close()`
  discarded the batch — two true quotations joined by an inference nobody ran, filed upstream as
  [booblik#68](https://github.com/youndie/booblik/issues/68) and closed as not confirmed.
- **A recorded decision is a fact about the past; the registry is the fact about now.** Research §1.7
  concluded booblik had no native client from a decision in booblik's own research that a later
  milestone had superseded without amending. Reading a build file tells you what a project publishes;
  only `repo1.maven.org` tells you where it landed. This cost a wrong decision (D5) and it is the
  reason §1.12 exists.
- **A pool check runs a statement, not an `acquire()`.** A pool hands out an idle connection whose far
  end is gone, and sqlx4k's pool has no `ping` to ask instead (research §1.9).
- **Every stage has its own deadline.** One shared budget is a budget the first stage can spend.
- **`ENTRYPOINT` in exec form, always.** Shell form makes `/bin/sh -c` PID 1, and it does not forward
  `SIGTERM` — so the process never sees the signal and the run looks like an instant clean shutdown.
- **A clean `SIGTERM` shutdown exits `0` on Kotlin/Native and `143` on the JVM.** Both are right.
  Never assert a specific exit code across the two; assert that the process ended itself and was not
  `SIGKILL`ed (`137`).
- **Commit EVERYTHING before you mutate, and rebuild after you revert.** `git checkout -- <dir>`
  restores the whole directory to HEAD, so a mutation revert silently deletes every *uncommitted*
  edit beside the mutated line. It has happened twice. In B-11 three production edits vanished and CI
  caught it. In B-22 the production code **was** committed first — and a test written afterwards was
  not, so the revert deleted the assertion the next mutation was being judged by, and that mutation
  "survived". Committing the change is not enough; commit whatever you wrote since.
- **After a mutation, check the test you are relying on actually ran.** Read the result file for its
  name. A test that was deleted, never compiled in, or served from the build cache is indistinguishable
  from a surviving mutant — all three look like a green build. In B-22 a 621 ms "BUILD SUCCESSFUL"
  with `linuxX64Test UP-TO-DATE` was the Gradle cache answering, not the suite.
- **An assertion whose power depends on the ambient environment works until the day it matters.**
  Two tests of the environment walk passed against a deliberately broken one: "PATH is among the
  names" catches a dropped first entry only if PATH is first, and "every listed name resolves" catches
  a wrong split only if some value contains an `=`. The fix was a *second implementation* —
  `/proc/self/environ` — not a better guess.
- **Rebuild the image, not just the binary.** An experiment against a container measures whatever is
  in the image. Rebuilding the Kotlin and re-running produced four cells of results about the
  previous build, consistently and convincingly.
- **A control has to be able to fail for the reason it is testing.** Twelve consistent runs of the
  negative control demonstrated nothing about research §1.1, because its stop subscriber closed
  nothing. Before believing a green control, ask what would have to be true for it to go red.
- **`runTest`'s clock is virtual, so never wait on a real thread inside it.** `withTimeout` there
  expires without a microsecond of wall time passing, and the test fails against a mechanism that
  works. Use `runBlocking` when the thing being awaited is a thread, a socket or a signal.
- **Measure a task with `--no-build-cache --rerun-tasks`.** `org.gradle.caching=true` is on here, so
  `clean` plus a timed run measures the cache: the native release link came out at 0.76 s that way
  and at 35.3 s when actually run.
- **A stage detaches its participants rather than joining them.** `withTimeout` around a
  `coroutineScope` still joins the children on the way out, so one participant that ignores
  cancellation makes the whole time bound a lie. Cancel the scope and move on: a leaked coroutine in
  a process that is exiting costs nothing, a `SIGKILL` mid-drain costs a request.
- **No commas in a backticked test name.** Kotlin/Native refuses them with
  `Name contains illegal characters: ","`, and the JVM target compiles them happily — so the failure
  arrives from a target you were not thinking about.
- **A number that was not measured says so.** The five-second pre-drain default is a hypothesis with
  an address ([B-20](docs/backlog/B-20-pre-drain-default.md)), and it is written as one in the table
  that ships it.
- **A measurement is a comparison.** Sample against control, alternating, median of several runs, the
  first run after a restart discarded explicitly. A ratio without an absolute decides nothing, and a
  figure in a README is a figure nothing updates.
- **The oracle asserts from the client's record, never from the server's log.** A log line is written
  by the code under test; an oracle that reads it can be satisfied by a comment.
- **A green run that visited nothing is the failure mode here.** The end-to-end oracle counts
  requests in flight at the signal and reports **inconclusive** below a floor. Any new gate gets the
  same treatment.
- **Do not fork a toolkit.** A gap goes upstream as an issue
  ([docs/research/research-upstream-proposals.md](docs/research/research-upstream-proposals.md)), kore
  works around it locally, and the workaround carries a comment naming the issue — so the next person
  deletes it instead of inheriting it.
- **An issue outside `youndie/*` is asked about first.** Our own repositories are the working
  arrangement; anybody else's tracker costs them time and cannot be quietly withdrawn. The Ktor
  entries in the proposals document are written and deliberately not filed.
- **"Closed" and "fixed" are different claims and only the second is checkable.** An upstream entry
  is closed when the fix is read in the source *and* in the published artefact of the version kore
  resolves.

## Documentation

Format: [docs-bootstrap](https://github.com/youndie/docs-bootstrap). Documents in English, code in
English.

```bash
pip install pyyaml
LOCAL=1 make check
```

`make check` is the gate and CI runs exactly it — on a GitHub runner, where no hook and no `LOCAL=1`
are involved; the prefix above is only for running it here, beside a mutagen session. `make report`
is the two non-blocking reports, and `code_anchors` reporting most paths as rotten is **correct**
here: they are where the code will live, and the count going down is one way to watch the library
arrive. It becomes a gate when it reaches zero, not before.
