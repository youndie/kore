# CLAUDE.md — kore

One Kotlin Multiplatform library that owns the process lifecycle of a server binary: the ordered
shutdown, three probes, a typed configuration from the environment, one-call observability wiring,
and `/version`. **Native-first** — `linuxX64` and `linuxArm64` decide the design; `jvm` and
`macosArm64` follow.

**There is no code yet.** This repository is research, a backlog and the layer documents. Treat every
path under `kore-*/` or `samples/` mentioned in a document as *where the code will live*.

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
     never uses `addShutdownHook` and installs `sigaction` itself;
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
- **Flush before close, always.** booblik's `Producer.close()` completes queued records
  *exceptionally* rather than sending them (research §1.8). "Close the consumers" is two verbs and
  everyone omits the first.
- **A pool check runs a statement, not an `acquire()`.** A pool hands out an idle connection whose far
  end is gone, and sqlx4k's pool has no `ping` to ask instead (research §1.9).
- **Every stage has its own deadline.** One shared budget is a budget the first stage can spend.
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
make check
```

`make check` is the gate and CI runs exactly it. `make report` is the two non-blocking reports — and
`code_anchors` reporting most paths as rotten is **correct** here: they are where the code will live,
and the count going down is one way to watch the library arrive. It becomes a gate when it reaches
zero, not before.
