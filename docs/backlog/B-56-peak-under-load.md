---
id: B-56
title: "RSS at ready is not the number a limit is set from"
status: open
priority: P2
size: S
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-56 — RSS at ready is not the number a limit is set from

`measure` samples `VmRSS` once, at the moment the subject first answers `/health`, and that is the
figure the README publishes. It answers *how big is it when it starts*.

A container limit is set from a different question — *what will the kernel kill it for* — and on this
subject the two are a factor of three apart even with the allocator recipe in
([B-55](B-55-allocator-page-size.md)): 8.8 MB at ready against ~27 MB under eight connections, and
~110 MB without the recipe. **A reader sizing a pod from the published number sets a limit the
process walks through on its first burst of traffic.**

The source is the cgroup's `memory.peak`, read at the end of the load phase. It exists on this host
and the harness already knows the container's PID, so the mechanism is a few lines — what makes this
an item rather than a patch is that it changes what a published cell *means*, and every number in two
write-ups and a README would have to be re-taken and re-labelled together.

**Not `docker stats`**, for the reason already written on `Container.rssKb`: it reports the cgroup's
memory including page cache. `memory.peak` is the high-water mark of what is charged, which is the
quantity the limit is compared against.

- AC: the harness reports a peak alongside RSS at ready, read from the cgroup, and the published
  tables carry both with the difference between the two questions stated.
- AC: a positive control — the same image under a deliberately small limit must be killed, or the
  harness cannot detect a failure at all.
- Anchors: `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Container.kt`,
  `samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Measure.kt`
