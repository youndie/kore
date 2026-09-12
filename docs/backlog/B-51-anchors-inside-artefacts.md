---
id: B-51
title: "An address inside an artefact is not a rotten anchor"
status: done
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

## Done — 2026-09-12

Changed **in docs-bootstrap** ([#7](https://github.com/youndie/docs-bootstrap/pull/7)) and copied
here; the five scripts are byte-identical again. An address inside an artefact carries the artefact
before a `!/` and is reported in its own section, never as rot:

```
Anchors: 114 - found 80, not found 0, inside artefacts 15, skipped 19
Every anchor resolves
```

**The escape hatch is narrow on purpose.** The left side must name something fetchable — a versioned
file, a Maven coordinate, or `owner/repo` — or the address is reported *missing*, saying why.
Otherwise "put a `!` in it" becomes the way to silence any anchor that really has rotted. All three
branches were exercised before the change was believed: a valid address, `Ktor!/…` refused by name,
and a placeholder skipped as a pattern.

### Rewriting the addresses found a claim the document could not support

§1.3 opened with *"Verified against `ktor-server-core-linuxx64-3.5.2-sources.jar`"* and then cited
`jvmMain/io/ktor/server/engine/ShutdownHookJvm.kt`. A target's sources jar carries `commonMain` plus
**that target's** source sets, so the named artefact cannot contain a `jvmMain` file. Unpacked both
to check rather than guessing:

| Artefact | Holds |
|---|---|
| `ktor-server-core-linuxx64-3.5.2-sources.jar` | `commonMain/…/ShutdownHook.kt`, `posixMain/…/ShutdownHookNative.kt` |
| `ktor-server-core-jvm-3.5.2-sources.jar` | `commonMain/…/ShutdownHook.kt`, `jvmMain/…/ShutdownHookJvm.kt` |

The facts were right and the attribution was not — which is the point of making an address name its
own artefact instead of inheriting one from a sentence above the table.

### Still not a gate

Zero is reachable now, and the two sentences this repository has had about that were wrong in
opposite directions — *"they are where the code will live"*, then *"it has no zero to reach"*. The
remaining objection is the one that was always the real one: a path quoted **as obsolete** is
indistinguishable by machine from a live one, and what rots lives in other people's repositories, so
a red build here is one nobody here caused. Turning `--check` on for the scheduled run is a separate
decision that needs evidence about obsolete-path citations, not a third promise.

- AC: the anchors report separates "inside an artefact" from "not found", and kore's copy of the
  script is still byte-identical to docs-bootstrap's. **Met.**
- Anchors: `scripts/code_anchors.py`, `docs/research/research-architecture.md`
