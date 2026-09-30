---
id: B-67
title: "Run kore-build's own check in CI — its tests and plugin validation have never run there"
status: open
priority: P2
size: XS
stage: m5-wiring
---

# B-67 — Run kore-build's own check in CI — its tests and plugin validation have never run there

`kore-build` is an included build (`settings.gradle.kts`, `includeBuild("kore-build")`), so the root
`./gradlew build` that CI runs does not reach it. `GitFactsTest` and `CompilationInputTest` run only
when somebody runs them by hand, and so does `validatePlugins` — which is red on `main` today:

```
Type 'io.github.youndie.kore.gradle.GenerateBuildIdentity' must be annotated either with
@CacheableTask or with @DisableCachingByDefault
```

Found by [B-66](B-66-built-at-from-source-date-epoch.md), whose tests would otherwise be the next
ones nothing runs. `publish.yaml` already knows the shape (it names `:kore-build:` tasks explicitly,
after #58).

- **`:kore-build:check` as a step of the check workflow**, beside `make build`, and the annotation
  `validatePlugins` asks for — `@DisableCachingByDefault`, with the reason the task already documents:
  it regenerates on every build, one small file compared by content.
- Does not cover: moving `kore-build` out of `includeBuild`.

- AC: the check workflow runs `:kore-build:check`; a run on the pull request shows `GitFactsTest`
  executed and `validatePlugins` green.
- Anchors: `.github/workflows/check.yaml`, `kore-build/src/main/kotlin/io/github/youndie/kore/gradle/BuildIdentityPlugin.kt`
