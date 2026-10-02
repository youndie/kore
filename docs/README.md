# docs — kore

kore is one library that gives every Kotlin server binary the same process lifecycle: an ordered
shutdown, three real probes, a typed configuration read from the environment, one-line wiring of the
three observability agents, and a `/version` that names the commit. The documentation is layered;
links run top to bottom.

```
[ Research — why the architecture is this and not that; verified vs hypothesis ]
                              │
[ Feature — what the library does and why, + BDD scenarios = acceptance criteria ]
                              │
[ API — the routes kore mounts, with their auth tier ]
                              │
[ Service — the modules, how they are built, and the sample they are judged by ]
```

There is **no `screens/` layer** and there will not be one: kore has no client. There is an `api/`
layer, with one document, because the routes kore mounts into somebody else's application are a
contract rather than an implementation detail.

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified, what is a hypothesis | the artefacts each fact names |
| Feature | `features/` | *what* the library does and *why*; BDD scenarios | this repository |
| API | `api/` | the routes, their status codes, and what is deliberately not promised | the contract classes named in the document |
| Service | `services/` | the modules, how they are built, the sample | this repository |

**Backlog** — [backlog.md](../backlog.md): the goal, the stages and the index; the items themselves
are one file each in [`backlog/`](backlog/), cited as
[B-03](backlog/B-03-negative-control.md).

## Read this first

**Every document in `features/`, `api/` and `services/` describes code that exists**, and each one
opens with a note saying what is built and what it deliberately leaves to a consumer. This paragraph
said the opposite — *"nothing described here is built"* — for weeks after it stopped being true, which
is the failure CLAUDE.md warns about in its own words: a sentence about the state of a project has no
way to fail. The documents and the build do; prefer them, and `backlog.md` for what is next.

What is *verified* rather than merely built is `research/`: every fact in [research-architecture](research/research-architecture.md) §1 was read in
an artefact that exists — the published sources of Ktor 3.5.2, the Kotlin/Native 2.4.10 platform
klibs, sqlx4k 1.13.0, the Kubernetes documentation, and the code of the toolkits kore has to wire
together.

The distinction is the point of the whole tree. A document that cannot establish something says it
does not cover it; if you find one that blurs a plan into an observation, that is a defect worth a
backlog item, because both halves then look equally authoritative.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity.
- BDD scenarios are written from behaviour, not from intent. While the code does not exist they are
  **target** behaviour and carry no `**Automated:**` line — the absence is the honest signal, and
  `bdd_report` counts it as manual.
- **The primary consumer is a coding agent.** Every document carries code anchors. A path outside
  this repository is written as an address — `<artefact>!/<path>` for a dependency's sources,
  `youndie/<repo>@<commit>!/<path>` for another repository of the portfolio — because CI checks the anchors
  with this clone alone, and a bare path into a sibling resolves only on a laptop that has it.
- Do not duplicate what lives in code: give the path. A copy rots, a path does not.
- **Language: English**, documents and code alike. HTTP headers, environment variable names and
  identifiers verbatim.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.
Sections marked `<!-- optional -->` can be deleted.

## Checks

```bash
pip install pyyaml
make check
```

`make check` is the gate and CI runs exactly it; `make fix` regenerates the backlog index and fills
in missing coverage-map lines. `make report` is the two non-blocking reports. The checks are
[docs-bootstrap](https://github.com/youndie/docs-bootstrap)'s, at the version
`.github/workflows/check.yaml` pins, fetched into `.docs-bootstrap/` by the first run. On a push to the
default branch CI also runs `make docs-on-main`, which makes a draft an error there — on since
[B-35](backlog/B-35-draft-gate.md).

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with
no file behind it, fails `coverage_map.py`.

### Research (3)

- [x] [research-architecture](research/research-architecture.md) — the eleven verified facts, the
  eight decisions, the risks; the entry point to everything else
- [x] [research-oracle](research/research-oracle.md) — the acceptance experiment, the property test
  and the measurement plan, all written before the code
- [x] [research-upstream-proposals](research/research-upstream-proposals.md) — five findings in
  other people's code, what each would claim, and which of them may be filed without asking

### Services (2)

- [x] [kore-library](services/kore-library.md) — the five modules, why each boundary is where it is,
  and what kore deliberately is not
- [x] [sample-service](services/sample-service.md) — one source, a JVM binary and a native one; the
  instrument the library is judged by rather than a demonstration

### Features (8)

The core, and the reason the library exists:
- [x] [feature-ordered-shutdown](features/feature-ordered-shutdown.md) — five named stages with
  individual deadlines, imposed by kore because the engine's own order is inverted between platforms

What a deployment can ask the process:
- [x] [feature-health-probes](features/feature-health-probes.md) — three probes, three questions, and
  only readiness reads a dependency
- [x] [feature-build-identity](features/feature-build-identity.md) — `/version`, compiled in by a
  Gradle plugin because Kotlin/Native has no resources

What a deployment tells the process:
- [x] [feature-typed-config](features/feature-typed-config.md) — a schema, `--print-config`, and a
  refusal on an unknown variable under the declared prefix
- [x] [feature-observability-wiring](features/feature-observability-wiring.md) — tracy, metrik and
  katcher in one call, with the three different shutdown contracts they actually have

What the platform will not tell the process:
- [x] [feature-memory-budget](features/feature-memory-budget.md) — the cgroup limit read and handed
  over, because a Kotlin/Native binary has no `UseContainerSupport` of its own

What a common library leaks on the platform:
- [x] [feature-koin-wiring](features/feature-koin-wiring.md) — Koin without koin-ktor's scope per
  call, which leaves a native mutex in malloc on every request on Linux

What three services had each written for themselves:
- [x] [feature-mcp-endpoint](features/feature-mcp-endpoint.md) — an MCP endpoint for coding agents:
  token or nothing, the guard on the transport's route, the host allowlist, MCP's own JSON on the way
  out, and the one screening rule that is not a service's domain

### API (1)

- [x] [endpoint-kore-admin](api/endpoint-kore-admin.md) — the five routes kore mounts, the status
  code of every condition, and what the `503` deliberately does not promise
