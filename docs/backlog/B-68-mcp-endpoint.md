---
id: B-68
title: "The hardened MCP endpoint is copied into three services, and the copies have drifted"
status: done
priority: P1
size: M
stage: m6-release
epic: feature-mcp-endpoint
blocked_by: []
---

# B-68 — The hardened MCP endpoint is copied into three services, and the copies have drifted

metrik, tracy and katcher each mount an MCP endpoint for coding agents with
`io.modelcontextprotocol:kotlin-sdk-server` 0.15.0 and `mcpStatelessStreamableHttp`, and each wraps it
in the same lessons: no token means no endpoint, a guard in front because the SDK's routing cannot be
nested in `authenticate {}`, a host allowlist because the SDK's default is localhost only, a JSON
`401` instead of a login page. Three copies of a security boundary is three places for it to be wrong,
and reading them side by side showed they already disagree — on whether a bare token without `Bearer`
is accepted, on whether a missing `Host` passes, on whether the SDK's own host check is on when no
hosts are configured, and on the one workaround only one of them carries. The owner chose kore as the
home, beside `kore-ktor` and `kore-koin`.

- **One call, `installKoreMcp(config, serverInfo) { tools }`,** and the services keep their tools,
  their facades and their domain screens. What moved is what was the same in all three plus what one
  of them had found and the others had not.
- **The guard sits on the route, not in front of the application.** kore's guard is a route-scoped
  plugin on the transport's own routing node, so the router and the guard cannot disagree about which
  requests reach the transport: whatever the router resolves to it has been through the guard, whatever
  spelling the request line used.
- **The `protocolVersion` workaround moved, as a different fix.** katcher rewrote the *request* to dodge
  an SDK default; the cause turned out to be the application's own ContentNegotiation, so kore encodes
  the transport's responses with the SDK's `McpJson` on its route instead, and a tripwire test says when
  the SDK no longer needs it.
- **Of the trust screens, only the hidden-character rule moved.** The phrase lists, the field policies
  and the two-phase gates differ between tracy and katcher on purpose — a log line is prose in a way a
  stack trace is not — and stay in the services. The character set was the same rule in two diverged
  copies; kore's is their union plus the bidi marks and the Unicode Tags block, written down once with
  a test per member.

## What was read, not assumed

| Fact | Where verified |
|---|---|
| Routing skips empty segments and URL-decodes each segment, so `//mcp`, `///mcp` and `/%6Dcp` resolve to the route `/mcp` | `ktor-server-core-3.6.0-sources.jar!/commonMain/io/ktor/server/routing/SegmentedPath.kt:22-31` (and the fast path, `RoutingPathTree.kt:124`) |
| The stateless transport answers with `call.respond(payload)`, i.e. through the application's ContentNegotiation, and installs its own `McpJson` one only when there is none | `kotlin-sdk-server-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/server/StreamableHttpServerTransport.kt:563`, `.../KtorServerHelpers.kt:33-42` |
| `InitializeResult.protocolVersion` defaults to `LATEST_PROTOCOL_VERSION` (`2025-11-25`), and an unsupported requested version negotiates to it | `kotlin-sdk-core-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/types/initialize.kt:92`, `kotlin-sdk-server-0.15.0-sources.jar!/.../server/ServerSession.kt:355` |
| katcher's application `Json` omits defaults (kotlinx's own default); metrik's sets `encodeDefaults = true`; tracy's server installs no ContentNegotiation at all | katcher `core/src/commonMain/kotlin/io/github/youndie/katcher/Common.kt`, metrik `shared/src/commonMain/kotlin/io/github/youndie/metrik/wire/Frame.kt`, tracy `server/src/commonMain/kotlin/io/github/youndie/tracy/server/Application.kt` |
| The SDK's `DnsRebindingProtection` defaults to `localhost`, `127.0.0.1`, `[::1]` and is installed whenever `enableDnsRebindingProtection` is true — katcher left it on with no hosts, metrik and tracy turned it on only with hosts | `kotlin-sdk-server-0.15.0-sources.jar!/.../server/KtorServer.kt:517`, `.../HostValidation.kt` |
| tracy and katcher each carry a hand-written set of invisible characters for their screens, and the two sets differ | tracy `server/src/commonMain/kotlin/io/github/youndie/tracy/server/mcp/LogTrust.kt`, katcher `server/src/commonMain/kotlin/io/github/youndie/katcher/mcp/CrashTrust.kt` |

## What was measured, and where

- **Why the guard is on the route** (`KoreMcpPathTest`): a real CIO engine, raw request lines, no token.
  `//mcp`, `/%6Dcp`, `/%6dcp` and `///mcp` all reach the transport — each is served with the token — and
  kore's guard refuses each without it before the `Server` factory is called. The control is a guard that
  decides by comparing `request.path()` to `/mcp`: it answers all four with an initialize result.
- **The two `protocolVersion` reports reconciled** (`ProtocolVersionTest`): under an application `Json`
  that omits defaults, the bare SDK answers `2025-06-18` with the field and `2025-11-25` without it — the
  tripwire. With no application ContentNegotiation the field is there for every version, which is what
  tracy measured when it removed its shim. katcher's report and tracy's were both right about their own
  application.
- **Mutations, each run and each red where it should be** (JVM, 2026-10-01): the guard moved to an
  application interceptor keyed on the path string turns `KoreMcpPathTest` red and nothing else; the re-encoding removed turns the
  two `protocolVersion` tests red; the host check disabled turns its unit test red and the endpoint test
  red — with `403` from the SDK's own check, which is live behind kore's; the copies' bare-token
  leniency turns three tests red; a fallback token in place of "off" turns both "no endpoint" tests red.

## Done when

- [x] `kore-mcp` with `installKoreMcp`, `KoreMcpConfig`, `KoreMcpAuth` and `HiddenCharacters`, on jvm,
  linuxX64, linuxArm64 and macosArm64 — `KoreMcpAuthTest`, `KoreMcpEndpointTest`, `KoreMcpPathTest`,
  `ProtocolVersionTest`, `HiddenCharactersTest`.
- [x] [feature-mcp-endpoint](../features/feature-mcp-endpoint.md), the README and
  [kore-library](../services/kore-library.md) say what it does, what it leaves to a service, and the
  install-order rule.
- [ ] metrik, tracy and katcher moved onto it — tracked in their own repositories, after the release
  that carries this module.

## Not done here

- **Upstream.** The SDK answering through the application's ContentNegotiation, and a required field
  with a default value, are the SDK's to fix —
  [research-upstream-proposals](../research/research-upstream-proposals.md) §8, written and not filed:
  the tracker is not `youndie/*`.
- **OAuth 2.1 from the MCP specification.** A static bearer token is a deviation from the specification,
  chosen for single-tenant self-hosted services, and kore does not change that choice.
- **The services' screens.** Phrase lists, field policies, the two-phase gates and the notes an agent
  is shown stay where their domain is.
