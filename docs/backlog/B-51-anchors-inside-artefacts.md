---
id: B-51
title: "An address inside an artefact is not a rotten anchor"
status: question
priority: P3
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-51 — An address inside an artefact is not a rotten anchor

`code_anchors.py` reports 14 rotten anchors here. **Thirteen of them cannot be anything else:**

| Address | Where it actually lives |
|---|---|
| `commonMain/io/ktor/server/engine/ShutdownHook.kt` and four more like it | inside the Ktor klib/jar this project resolves |
| `klib/platform/linux_x64/…posix`, `klib/platform/macos_arm64/…posix` | inside the Kotlin/Native distribution |
| `commonMain/io/github/smyrgeorge/sqlx4k/ConnectionPool.kt` | inside the sqlx4k artefact |
| `content/en/docs/concepts/workloads/pods/pod-lifecycle.md` | in `kubernetes/website`, which is not checked out here |

These were verified by **unpacking the artefact** — which is exactly what research §1 is for, and the
strongest kind of address this project has. The checker searches sibling repositories for a file, so
it can only ever report them missing. That makes the count permanently non-zero, and made the
standing promise — *"add `--check` once the stale list reaches zero"* — one nothing could keep. The
promise is now removed from the workflow, the Makefile and CLAUDE.md.

The 14th was real: a `features/` row naming `samples/service/.../commonMain/.../Main.kt`, a file that
had never existed, claiming the sample used the configuration schema. It does not
([B-50](B-50-sample-uses-the-config-schema.md)). **One real finding in fourteen is the argument for
the report, not against it** — but it is also the argument for making the other thirteen stop
competing with it for attention.

## Why this is a question and not a fix

The fix is one rule in `code_anchors.py`: an address inside an artefact is a distinct status, not a
missing file — marked at the point of use, say `ktor-server-core-3.5.2.klib!/commonMain/…`, so the
address keeps its precision and the checker stops searching for it.

**kore's five scripts are byte-identical copies of docs-bootstrap's.** Changing this one here would
diverge it, and the next sync would revert the change without anyone noticing — the failure this
project has already written down twice under a different name. So the change belongs in
[docs-bootstrap](https://github.com/youndie/docs-bootstrap), and a change to another repository is a
question rather than a task.

**Rejected: abbreviating the addresses so the existing heuristic skips them.** `commonMain/.../ShutdownHook.kt`
does get skipped — that form is already in this document, next to the full form of the same file, which
is how the inconsistency was noticed. But it works by deleting `io/ktor/server/engine` from an address
whose whole value is precision, to satisfy a checker. Gaming a report is worse than a report with a
known floor.

- AC: the anchors report separates "inside an artefact" from "not found", and kore's copy of the
  script is still byte-identical to docs-bootstrap's.
- Anchors: `scripts/code_anchors.py`, `docs/research/research-architecture.md`
