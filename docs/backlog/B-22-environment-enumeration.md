---
id: B-22
title: "Environment enumeration per target, and the honest macOS gap"
status: open
priority: P0
size: M
stage: m4-config
epic: feature-typed-config
blocked_by: [B-21]
---

# B-22 — Environment enumeration per target, and the honest macOS gap

Lookup everywhere; enumeration where it exists.

- **The decision and its reason.** Research §1.5, all of it verified in the Kotlin/Native 2.4.10
  platform klibs: `platform.posix` exposes **`__environ`** on `linux_x64` and `linux_arm64` — not
  `environ` — and exposes neither on `macos_arm64`; `_NSGetEnviron` is not in `platform.posix`,
  `platform.darwin` or `platform.Foundation` either. So the unknown-variable check is a capability of
  the JVM and the Linux native targets, and on macOS native it is **unavailable**.
- **Rejected, and this is the whole point of the item:** returning "no unknown variables found" on
  macOS. A check that always passes is worse than an absent one, because a deployment reads it as
  evidence. `--print-config` states the capability of the target it is running on.
- **Rejected:** a cinterop `.def` for `_NSGetEnviron` to close the gap. It is buildable, and it would
  buy the enumeration on a target nothing is deployed to, at the price of a native interop step in the
  core module. Revisit only if macOS native stops being a development convenience.

- AC: enumeration works on `jvm`, `linuxX64` and `linuxArm64`; on `macosArm64` the capability reports
  itself absent and a test asserts that it does.
- Anchors: `kore-core/src/linuxMain/`, `kore-core/src/macosMain/`, `kore-core/src/jvmMain/`
