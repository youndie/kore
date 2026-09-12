---
id: B-49
title: "The priority target has never run a test"
status: wip
priority: P1
size: M
stage: m6-release
epic: feature-ordered-shutdown
blocked_by: []
---

# B-49 — The priority target has never run a test

`CLAUDE.md` opens by saying `linuxX64` and `linuxArm64` decide the design, and records honestly that
`linuxArm64Test` and `macosArm64Test` are **SKIPPED** inside a successful build. Both sentences have
been true together since the first green build: the library's own priority target has never executed
a single assertion.

Kotlin/Native runs a test binary only when its target is the host, so an x86-64 Linux runner compiles
those two targets and runs neither. Nothing is red, nothing is missing from the log, and `build`
going green says *compiles* where a reader hears *works*.

**What is actually at risk on those targets, rather than a general worry about coverage:** the signal
handler and `__environ` are `nativeMain` code that differs by libc and ABI; `Dispatchers.IO` was
already wrong once on Native (§1.14, B-42); and the drain's timing is the kind of thing a different
memory model reorders. None of that is visible from a compile.

## Real hosts, not an emulator

This repository is **public**, and GitHub charges for *larger* runners only — standard runners are
free to public repositories on every platform. So `ubuntu-24.04-arm` and `macos-14` each run their
own suite natively, which is strictly better evidence than qemu: an emulator is a second thing that
can be wrong, and a failure under one is a finding about the emulator until proved otherwise.

**Rejected: qemu-user on the existing x86 runner.** Cheaper to add and it answers a different
question. It would also have to be believed: a `SIGTERM` delivered by an emulated kernel interface is
exactly the mechanism this library is about, and the emulator is the last place to test it.

**Rejected: making `make build` run them.** A contributor on a Mac cannot run `linuxArm64Test` and a
contributor on Linux cannot run `macosArm64Test`. The rule that CI runs the contributor's command
holds where a contributor *has* one; here the honest thing is a job that says what it does.

## The cache key was already wrong for this

`~/.konan` is keyed on `runner.os`, which is `Linux` for both the x86-64 and the arm64 runner. Adding
a second Linux architecture to the same key would have had the two poisoning each other's cache —
silently, because a restored cache for the wrong architecture looks like a slow first build rather
than an error. The key carries `runner.arch` now.

- AC: `linuxArm64Test` and `macosArm64Test` run on every pull request, on hosts where they are not
  skipped, and their result files are read rather than their exit codes trusted.
- AC: a suite that ran zero tests fails the job.
- Anchors: `.github/workflows/check.yaml`
