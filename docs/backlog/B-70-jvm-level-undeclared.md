---
id: B-70
title: "The JVM variants need Java 25 and their Gradle metadata does not say so"
status: done
priority: P1
size: S
stage: m6-release
blocked_by: []
---

# B-70 — The JVM variants need Java 25 and their Gradle metadata does not say so

Reported as [#115](https://github.com/youndie/kore/issues/115) by a KMP library whose JVM target is
21 and which took `kore-core` 0.1.12 for one module. Its build was green; its first JVM test that
loaded a kore class died on `UnsupportedClassVersionError` (class file 69, runtime up to 65).

Checked on the published artefacts, not on the build: `kore-core-0.1.14.module` has no
`org.gradle.jvm.version` on `jvmApiElements-published` or `jvmRuntimeElements-published`, and every
class in `kore-core-jvm-0.1.14.jar` is major 69. The multiplatform plugin does not stamp the
attribute on a `jvm()` target's variants, unlike the `java` and `kotlin("jvm")` plugins, so nothing
told Gradle what `jvmToolchain(25)` had compiled to.

The issue offered two fixes — declare 25, or compile lower. B-43 had already named the second as
the move to make once an outside consumer asked ("a build change and a re-run of the suite"), and one
had. What it did not know is that the answer is not one number for the whole library. Read off the
dependencies' own jars and metadata on 2026-10-03:

| Dependency | Bytecode | Declared `jvm.version` |
|---|---|---|
| koin-core 4.1 | 52 (8) | — |
| kotlin-sdk-server 0.15.0 | 55 (11) | — |
| tracy agent 0.2.15, metrik agent 0.3.21, katcher client 0.7.47 | 69 (25) | 25 |
| booblik-client 0.3.4 | 69 (25) | 25 |

So `kore-observability` and `kore-booblik` cannot run below 25 whatever kore compiles them to, and
their dependencies already make Gradle refuse a lower consumer. Compiling those two for 21 would be a
declaration the classpath breaks.

- **`kore-core`, `kore-ktor`, `kore-koin`, `kore-mcp` compile for 21** (`jvmTarget` in the catalog),
  with `-Xjdk-release=21` because the toolchain stays 25 and would otherwise let a JDK 22+ API
  through. **`kore-observability` and `kore-booblik` stay at 25.**
- **Every library module declares its level** on `jvmApiElements`/`jvmRuntimeElements`, so a consumer
  below it fails at resolution with the version named.
- **`checkJvmLevel`, part of `check`,** reads every class in the JVM jar and every JVM library
  variant in the root and the jvm module files, and fails when they disagree or when it finds no
  variants.
- Does not cover: the Gradle plugin (`kore-build`), which declares 25 already through the
  `java-gradle-plugin` path, so a daemon below 25 is refused at resolution rather than at run time.

- AC: the generated module files of the four modules declare 21 and of the two declare 25; the four
  modules' JVM suites pass on a JDK 21 launcher; `checkJvmLevel` goes red on a declared level that
  is not the bytecode's and on bytecode that is not the declared level.
- Anchors: `build.gradle.kts`, `gradle/libs.versions.toml`

## Done, 2026-10-03

On the Linux box, read from `build/publications/*/module.json`: `kore-core`, `-ktor`, `-koin`, `-mcp`
declare 21 on both JVM variants in both files, `kore-observability` and `kore-booblik` 25.

**On Java 21 for real**, through a throwaway init script that set the test launcher to the box's JDK
21 (`--rerun-tasks --no-build-cache`, launcher printed by each task): `kore-core` 124 tests,
`kore-ktor` 37, `kore-koin` 4, `kore-mcp` 36 — 201, no failures, counted from the result files. It is
not a permanent gate: the suite runs on 25, where the portfolio's services run, and one launcher per
task is all Gradle gives.

**The check can fail, both ways**, with each mutation committed against before it was made:

- `kore-core` declaring `level + 1`: `JVM variants declare org.gradle.jvm.version [22, 22], the
  bytecode is 21`;
- `kore-ktor` compiled for 25 under a declared 21 (with `-Xjdk-release` dropped — kept, the mismatch
  is refused by the compiler before the check sees it): `expected Java 21 bytecode, found {25=17}`.

Released in the next publish; 0.1.14 and earlier stay as they were.
