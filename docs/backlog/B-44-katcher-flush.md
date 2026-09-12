---
id: B-44
title: "katcher can be flushed now — call it"
status: done
priority: P1
size: S
stage: m5-wiring
epic: feature-observability-wiring
blocked_by: []
---

# B-44 — katcher can be flushed now — call it

**The upstream question came back answered, with a fix.** [§5 of the upstream
proposals](../research/research-upstream-proposals.md) asked katcher's owner whether a server binary
with no next launch should be able to make its crash report leave the process. The answer is
[youndie/katcher#51](https://github.com/youndie/katcher/pull/51), merged 2026-09-12:

* **`Katcher.flush(grace): Boolean`** — suspend, meant to be called last in a telemetry group, and it
  answers whether the queue emptied. `false` means the report is still on disk.
* **`KatcherConfig.cacheDir`** — the queue's directory, so it can be a volume rather than a working
  directory that dies with the pod.
* **`KatcherConfig.crashUploadGrace`** — how long the fatal path holds the dying thread. `ZERO` by
  default, so a mobile client behaves exactly as before.

- **What this makes false in kore's own documents**, which is the work:
  [feature-observability-wiring](../features/feature-observability-wiring.md) §3 says katcher offers
  *"`start` and nothing else — no `stop`, no `flush`"* and that kore therefore has *"nothing to
  call"*; §7 repeats it. Both were true when written and are not any more.
- **The decision and its reason.** kore's telemetry group gets `Katcher.flush(grace)` with kore's own
  deadline, the same treatment tracy already has. The `Boolean` is not discarded: `false` is the one
  case an operator can act on — the reports are on disk and the volume has to outlive the pod.
- Does **not** cover: `crashUploadGrace`, which is the *fatal* path rather than the shutdown path and
  belongs to whoever owns the entry point ([B-30](B-30-entry-point-question.md)). kore's
  `ObservabilitySettings` should carry `cacheDir` though — a queue in a working directory is the
  reason the question was asked.

- AC: the version pin moves to one that has `flush`, checked by compiling rather than by reading a
  changelog; the telemetry participant calls it and does not discard the result; §3's table and §7's
  quirk are corrected at the point of divergence; the upstream entry records that it was answered.
- Anchors: `kore-observability/src/commonMain/kotlin/io/github/youndie/kore/observability/`,
  `docs/research/research-upstream-proposals.md`

## Done — and the test is the part worth reading

The pin moved to `0.7.47` by compiling against it. The telemetry group now flushes **tracy and
katcher concurrently**, each bounded by the same `flushGrace`: sequentially tracy's last flush could
spend the whole budget and hand katcher a deadline that had already passed, and a stage deadline is
not a queue.

`KatcherFlushTest` earns its place by refusing first. Against a receiver that answers `200`
immediately, katcher's own background uploader delivers the report in milliseconds and the assertion
**passes with the `flush` call deleted** — a test of katcher's worker wearing kore's name. That was
checked rather than assumed. So the receiver refuses until the uploader has given up; from that
moment the report moves only if something asks, and the only thing that asks is the telemetry stage.

**The queue is pointed out of the source tree**, and that is not tidiness. katcher's default cache
directory sits beside the working directory, which under Gradle is the module directory — inside the
one-way sync to the build box. A file the box creates and the laptop does not have is deleted *while
the test runs*: the first version watched the uploader fail three times and then found an empty
queue, with `flush` answering `true` because there was genuinely nothing left. It is the same reason
a deployment needs `cacheDir` on a volume: a directory somebody else can empty is not a queue.

## What is deliberately not done

**The `Boolean` is discarded.** `launch { Katcher.flush(flushGrace) }` does not read the answer, so
`false` — the reports are still on disk, and the volume had better outlive the pod — reaches nobody.
kore has no logger of its own and `KoreObservability.stop()` does not hold the application, so saying
it costs plumbing rather than a line. Left as it stands; it is the one clause of this item's
acceptance that is not met, and it is recorded here rather than quietly dropped from the criteria.
