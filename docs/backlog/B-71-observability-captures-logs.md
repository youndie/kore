---
id: B-71
title: "kore-observability wires tracy and attaches no log capture, so only spans arrive"
status: done
priority: P1
size: S
stage: m6-release
blocked_by: []
---

# B-71 — kore-observability wires tracy and attaches no log capture, so only spans arrive

Found from tracy's side (youndie/tracy M-150, 09.11.2026): konekt-server on the stand had
`storedRecords = 0` next to 467 MB of `producedBytes` for six weeks, while its spans arrived.
`installKoreObservability` installs tracy's agent, plugin and delivery and nothing else. The plugin
writes the span of each request; every line the service logs reaches tracy only through a capture —
`TracyLogbackAppender` on the JVM, kotlin-logging's appender on native — and kore attached neither.
Its own KDoc named `agent.logger("name")` as the only way in, which is true and was the problem: a
consumer that logs through SLF4J or kotlin-logging, as every consumer does, got spans only.

## Decision

- `installKoreObservability` attaches the capture whenever tracy is on: logback's root logger on the
  JVM, `KotlinLoggingConfiguration.direct.appender` on native. `stop()` takes it off first.
- **From `WARN` by default** (`ObservabilitySettings.tracyCaptureFrom`, `null` = none). A captured line
  carries no trace, so tracy cannot tail-sample it and sends every one — from `INFO` that is a line
  per request. haul, which attaches tracy's appender itself, settled on `WARN` for the same reason.
  The floor is a `RecordSink` wrapper, so both platforms are bounded by one rule.
- Not an environment variable: more than warnings is a volume decision, and it belongs in code.
- **Absent says so.** No logback as the SLF4J backend, or kotlin-logging not on its direct factory
  (Apple targets — os_log) → nothing attached and `KoreObservability.logsCaptured == false`.
- Stays on tracy agent 0.2.15: `RecordSink`, `TracyAppender` and `TracyLogbackAppender` are public
  there. Moving to 0.3.18 is separate — the server plugin moved to `agent-ktor-server` in it.

## Acceptance

- [x] a warning logged through SLF4J (JVM) or kotlin-logging (linuxX64) reaches tracy, an info line
  does not — `LogCaptureTest`;
- [x] detached, the service's logging no longer reaches tracy;
- [x] the wiring attaches by default and attaches nothing with `tracyCaptureFrom = null`;
- [x] on macosArm64 the capture reports itself absent;
- [x] `feature-observability-wiring` rule 7a and a scenario.

## Consumers

konekt moves to the release that carries this (youndie/tracy M-150 waits on it). A consumer that
already attaches `TracyLogbackAppender` itself would capture twice — remove its own on the move.
