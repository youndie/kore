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

## What the first run found: the two targets are not in the same state

`CLAUDE.md` called both of them **SKIPPED**, one word covering two different things, and only one of
them was true.

| Target | What the plugin actually does on an x86-64 Linux host |
|---|---|
| `macosArm64` | the task **exists** and is disabled — `Task 'macosArm64Test' for target 'macos_arm64' cannot run on the current host (linux_x64)`, printed as a warning inside `BUILD SUCCESSFUL` |
| `linuxArm64` | the task is **never created**. `tasks --all` lists `linuxArm64TestBinaries` and `linuxArm64TestKlibrary` and no `linuxArm64Test` |

The Kotlin Gradle plugin gives a native target a test task only where Kotlin/Native supports that
target as a **host**, and `linux_arm64` is not one. So the first version of this job — "run
`linuxArm64Test` on an arm64 runner" — failed there in 57 seconds with `Task 'linuxArm64Test' is
ambiguous ... Candidates are: linuxArm64TestBinaries, linuxArm64TestKlibrary`, which is Gradle saying
the name belongs to nothing. **No runner of any architecture can be told to run that task**, and a
year of "it is skipped on this host" had hidden that there was nothing to skip.

## Real hosts, not an emulator

This repository is **public**, and GitHub charges for *larger* runners only — standard runners are
free to public repositories on every platform. So `ubuntu-24.04-arm` and `macos-14` each run their
own suite natively, which is strictly better evidence than qemu: an emulator is a second thing that
can be wrong, and a failure under one is a finding about the emulator until proved otherwise.

**Rejected: qemu-user on the existing x86 runner.** Cheaper to add and it answers a different
question. It would also have to be believed: a `SIGTERM` delivered by an emulated kernel interface is
exactly the mechanism this library is about, and the emulator is the last place to test it.

For `linuxArm64` that means separating linking from running, which is all the plugin does for the
other targets anyway: the x86-64 job cross-links `test.kexe` — an aarch64 ELF, confirmed with `file` —
and an arm64 runner executes it. That side needs no Gradle and no Kotlin/Native distribution.

**Rejected: making `make build` run them.** A contributor on a Mac cannot run `linuxArm64Test` and a
contributor on Linux cannot run `macosArm64Test`. The rule that CI runs the contributor's command
holds where a contributor *has* one; here the honest thing is a job that says what it does.

## The cache key was already wrong for this

`~/.konan` is keyed on `runner.os`, which is `Linux` for both the x86-64 and the arm64 runner. Adding
a second Linux architecture to the same key would have had the two poisoning each other's cache —
silently, because a restored cache for the wrong architecture looks like a slow first build rather
than an error. The key carries `runner.arch` now.

- AC: `macosArm64Test` runs on every pull request on a host where it is not disabled, and its result
  files are read rather than its exit code trusted.
- AC: every `linuxArm64` test binary the build produces is executed on an arm64 machine on every
  pull request, and the job fails if none was produced or if they ran zero tests.
- AC: a suite that ran zero tests fails the job.
- Anchors: `.github/workflows/check.yaml`
