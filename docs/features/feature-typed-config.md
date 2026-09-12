---
id: feature-typed-config
title: Typed configuration from the environment
type: feature
status: draft
owner: unassigned
involved_services:
  - kore-library
  - sample-service
client_entries: []
api: []
tags: [configuration, environment, startup]
---

# Typed configuration from the environment

> **`status: draft`** — designed, not built. The platform facts it rests on are verified and sourced
> in [research-architecture](../research/research-architecture.md) §1.5; the baseline it generalises
> is §1.11.

## 1. Overview

A service declares its configuration as a typed schema — name, type, required or defaulted, secret
or not — and kore reads the environment against it once, at startup. A missing required variable is a
process that does not start. A value that does not parse is a process that does not start. A variable
under the service's own prefix that the schema does not declare is a process that does not start.
`--print-config` prints what was resolved, where each value came from, and what is masked.

The reason is the incident class: half of *"production is running something other than what we
think"* is a configuration that was read differently from how it was written — a typo that fell back
to a default, a boolean that was `"True"`, a variable that was renamed in the chart and still read by
the old name in the code. None of those produces an error today; all of them produce a service that
starts.

## 2. Business rules

1. **One source: the environment.** No files, no remote configuration, no precedence rules. The value
   here is the schema and the refusal, and every additional source is a rule about which of two
   values wins — which is a new way to be wrong about what is running.
2. **Everything is read once, before anything serves.** A missing value is a process that will not
   start, not a route that fails later under a user. This is the first consumer's rule already
   (research §1.11) and kore takes it rather than inventing it.
3. **An unknown variable under the declared prefix fails the start.** *Under the prefix* — see rule 4.
4. **The check is scoped to a prefix, and the prefix is part of the schema.** A container's
   environment carries `PATH`, `HOSTNAME`, `KUBERNETES_SERVICE_HOST` and every `*_PORT` the kubelet
   injects. A check over the whole environment would fail on its first deployment, be switched off,
   and never be switched on again.
5. **A pair that must be set together is declared as a pair.** The first consumer has this rule
   hand-written for three agents — an endpoint without its key is a refusal, because *"one without
   the other is a deployment that believes it is observed and is not"* (research §1.11). kore makes
   it a declaration rather than three copies of an `if`.
6. **A boolean means the exact string `true`.** Anything else, including `"True"` and `"1"`, means
   false — and an unset switch always means the closed position. A security switch that opens on a
   misspelling is the switch that ships open.
7. **A secret is declared as a secret and is masked wherever the configuration is rendered.** Masking
   is a property of the field, not a list of names somebody keeps in sync with the schema.
8. **Where a value came from is part of the answer.** `--print-config` prints `default`, `env` or
   `code` beside every value. "The default was used" and "the environment said the same thing as the
   default" are different facts, and the difference is what a rename looks like.
9. **`--print-config` exits without serving.** It is asked most often *because* the process will not
   start, so it must not need a process that started.

## 3. Why a flag and not a route

A route needs a running service, a port, and reachability. The question — *what does this deployment
think it is configured as* — is asked in CI, in a `kubectl run` against the image, in the one-shot
migration container the portfolio already runs before its pods roll, and on a laptop. A flag works in
all of them; a route works in one of them, and not in the case that matters most.

## 4. kore's own keys

kore reads its own settings under `KORE_`, through this same mechanism — so kore's configuration is
the first consumer of the feature. The keys are declared in the schema and printed by
`--print-config`; they are not copied here, for the reason every other document in this repository
gives paths instead of lists. What is worth stating is the shape: the five stage deadlines of
[feature-health-probes](feature-health-probes.md) §4, the grace period kore should assume, and the
`/version` reduction switch of [endpoint-kore-admin](../api/endpoint-kore-admin.md).

A service's own prefix is declared by its schema and is never `KORE_`. The two namespaces are
separate so that a service can be strict about its own variables without becoming strict about the
library's.

## 5. Code anchors

| Service | Code |
|---|---|
| kore-library | `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/` — the schema DSL, the reader, the renderer |
| kore-library | `kore-core/src/jvmMain/kotlin/io/github/youndie/kore/config/Environment.jvm.kt` — `System.getenv()` |
| kore-library | `kore-core/src/linuxMain/kotlin/io/github/youndie/kore/config/Environment.linux.kt` — enumeration through `__environ` |
| kore-library | `kore-core/src/macosMain/kotlin/io/github/youndie/kore/config/Environment.macos.kt` — lookup only; see §7 |
| sample-service | `samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/Main.kt` — a schema with a required field, a default, a secret and a near-miss name |

## 6. Scenarios (BDD)

**All nine are automated as of B-24.** Two were deliberately held back on the way here because they
would have passed **vacuously**: "a variable outside the prefix is not the schema's business" until
there was an unknown check for it to survive (B-23), and the near-miss until that check became a
**refusal** rather than a listing (B-24).

### Scenario: a missing required variable stops the process
* **Given:** a schema with a required `SAMPLE_STORE_URL` and nothing set
* **When:** the process starts
* **Then:** it refuses to start
* **And:** the message names `SAMPLE_STORE_URL`
* **Automated:** `ConfigSchemaTest`

### Scenario: a value that does not parse stops the process
* **Given:** `SAMPLE_TIMEOUT_MS` declared as an integer and set to `soon`
* **When:** the process starts
* **Then:** it refuses to start and names the variable and the expected type
* **Automated:** `ConfigSchemaTest`

### Scenario: a near-miss name is refused, not ignored
* **Given:** the schema declares `SAMPLE_TIMEOUT_MS` and the environment sets `SAMPLE_TIMEOUT_MSEC`
* **When:** the process starts on a target where enumeration is available
* **Then:** it refuses to start
* **And:** the message names both spellings
* **And:** this is the scenario an implementation that checks only *required* variables passes
* **Automated:** `UnknownVariableTest`

### Scenario: a variable outside the prefix is not the schema's business
* **Given:** the environment carries `PATH`, `HOSTNAME` and a dozen `*_PORT` variables the kubelet
  injected
* **When:** the process starts
* **Then:** it starts
* **And:** none of them is reported as unknown
* **Automated:** `PrintConfigTest`

### Scenario: a declared pair, half set, stops the process
* **Given:** `TRACY_ENDPOINT` set and `TRACY_KEY` unset, declared as a pair
* **When:** the process starts
* **Then:** it refuses to start and names both
* **Automated:** `ConfigSchemaTest`

### Scenario: a boolean means exactly "true"
* **Given:** a switch declared as a boolean and set to `True`
* **When:** the configuration is read
* **Then:** the value is false
* **And:** the same holds for `1`, `yes` and an empty string
* **Automated:** `ConfigSchemaTest`

### Scenario: --print-config shows origins and masks secrets
* **Given:** a schema with a defaulted field, a field set in the environment, and a secret
* **When:** the binary is run with `--print-config`
* **Then:** it prints each key with its value and one of `default`, `env` or `code`
* **And:** the secret's value does not appear anywhere in the output
* **And:** the process exits without binding a port
* **Automated:** `PrintConfigTest`

### Scenario: --print-config works on a configuration that cannot start
* **Given:** a required variable is missing
* **When:** the binary is run with `--print-config`
* **Then:** it exits non-zero with the same message the start would give
* **And:** it still prints what it did resolve
* **Automated:** `PrintConfigTest`

### Scenario: the unknown-variable check reports its own absence
* **Given:** a build for `macosArm64`
* **When:** the binary is run with `--print-config`
* **Then:** the output states that unknown-variable detection is unavailable on this target
* **And:** it does **not** report that no unknown variables were found
* **Automated:** `PrintConfigTest`

## 7. Out of scope

* **Configuration files, remote configuration, reload at runtime.** Rule 1. A value that can change
  under a running process is a value no `--print-config` can be trusted about.
* **Validation of meaning.** kore checks the type, the presence and the pairing. Whether a URL points
  at the right cluster is the service's question.
* **Secret management.** kore reads what the environment holds and masks what is declared secret. How
  a secret got into the environment is the chart's business.

## 8. Quirks

* **The unknown-variable check does not exist on macOS native, and it says so.** Research §1.5:
  `platform.posix` exposes `__environ` on `linux_x64` and `linux_arm64` and exposes neither `environ`
  nor `__environ` on `macos_arm64`; `_NSGetEnviron` is not in `platform.posix`, `platform.darwin` or
  `platform.Foundation` either. So enumeration is a capability of the JVM and the Linux native
  targets. On macOS native, lookup works and the unknown-variable check is **unavailable** —
  `--print-config` states that, rather than reporting "no unknown variables found", which a
  deployment would read as evidence. macOS native is a development target; the servers run on Linux.
* **The variable is `__environ`, not `environ`.** Writing the POSIX-documented name compiles nowhere
  on these targets, and the error names a missing symbol rather than a missing platform.
* **A "near-miss" name is the case worth testing.** `SAMPLE_TIMEOUT_MS` declared and
  `SAMPLE_TIMEOUT_MSEC` set is what the feature exists to catch, and it is the case where an
  implementation that only checks *required* variables passes. The sample's schema carries one
  deliberately.
