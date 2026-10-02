---
id: feature-mcp-endpoint
title: An MCP endpoint for coding agents, guarded once
type: feature
status: active
owner: unassigned
involved_services:
  - kore-library
client_entries: []
api: []
tags: [mcp, security, ktor, agentjacking]
---

# An MCP endpoint for coding agents, guarded once

> **Built** ([B-68](../backlog/B-68-mcp-endpoint.md)). `installKoreMcp` in `kore-mcp`, extracted from the
> three services of the portfolio that each mounted their own: metrik, tracy and katcher. kore owns the
> boundary — who gets in, what a refusal looks like, what JSON leaves — and nothing about what the tools
> do. The services keep their tools, their facades and their domain screens.

## 1. Overview

A service that wants a coding agent to read it mounts a Model Context Protocol endpoint: tools the
agent calls over HTTP, through `io.modelcontextprotocol:kotlin-sdk-server`'s stateless streamable-HTTP
transport. Around that one SDK call every service had written the same lessons, each learned in
production by one of them: a missing secret must mean *off*, the guard cannot be Ktor's `authenticate`,
the SDK's host check defaults to a value that only works on a laptop, and a machine client must get a
`401` rather than a login page. Three copies of a security boundary had already drifted apart.

```
installKoreMcp(KoreMcpConfig(token, allowedHosts), Implementation("metrik", version)) {
    registerTools(facade)          // the service's own: addTool(...) on the SDK's Server
}
```

`KoreMcpEndpointTest` is the worked example, through the path a service writes.

## 2. Business rules

1. **No token, no endpoint.** A null or blank token installs nothing — no route, no guard, no
   ContentNegotiation. A request to the path is a `404`. "Forgot to set the secret" must never mean
   "exposed it", including behind a proxy whose bypass for `/mcp` is configured and whose backend is not.
2. **The guard sits on the route the transport answers.** The SDK installs its own routing on the
   `Application`, so it cannot be nested in `authenticate {}`. kore installs a route-scoped plugin on the
   transport's own routing node before the transport registers there. An application interceptor would
   have to decide from the request path whether a call is the endpoint's, and Ktor's router does not
   resolve the raw path — it skips empty segments and URL-decodes each one — so `//mcp`, `///mcp` and
   `/%6Dcp` reach the transport while a comparison with `"/mcp"` says otherwise. On the route, the
   router's answer and the guard's are the same answer.
3. **A bearer token, the scheme required, compared in constant time.** `Authorization: Bearer <token>`,
   the scheme case-insensitive (RFC 6750 §2.1). A bare token is refused. The time spent depends only on
   the length presented. A header from a browser contour (`X-Auth-Request-*`) is never authorisation:
   the proxy that sets it accepts whatever identity is claimed.
4. **The host is checked only when hosts are configured.** Then a foreign, missing or malformed `Host` is
   a `400`, before the token is looked at. With none configured, `Host` is not read — the SDK's own
   default is localhost only, which refuses every deployed request and cannot fail on a laptop. The SDK's
   `DnsRebindingProtection` stays on behind kore's check when hosts are configured, with the same list
   and the same grammar.
5. **A refusal is a JSON code, never a redirect.** `401` with `{"error":"unauthorized"}` and
   `WWW-Authenticate: Bearer`; `400` with `{"error":"invalid host","host":…}`. The host is echoed for the
   operator who mistyped the list, JSON-encoded because it is whatever the caller sent.
6. **MCP messages leave in MCP's JSON.** The transport's JSON-RPC responses are encoded with the SDK's
   `McpJson` on the endpoint's route, before any ContentNegotiation the application installed sees them.
   SDK 0.15.0 answers a POST through the application's ContentNegotiation, so the application's `Json`
   decides what the client reads. One that omits defaults drops `protocolVersion` from the initialize
   result exactly when it equals the SDK's latest version — which is also what a version the SDK does
   not know negotiates to, and no current client connects without the field — and drops the whole
   `result` of a `ping`, which leaves an answer that is not JSON-RPC. Ktor's `json()` with no argument
   writes an explicit `null` for every unset optional field. The tripwire compares whole answers, not a
   field: its controls on the bare SDK fail the day the SDK stops handing its answer to the
   application's ContentNegotiation, and that is the day the re-encoding is deleted — not the day
   `protocolVersion` alone is fixed.
7. **The application's ContentNegotiation goes before `installKoreMcp`, or nowhere.** The SDK installs one
   with `McpJson` on the whole application when it finds none; a later `install` then throws
   `DuplicatePluginException`. kore does not hide this — it is the SDK's behaviour on the application,
   not on the endpoint.
8. **One screening rule is shared, and only one.** `HiddenCharacters` finds the code points a reader
   cannot see and a model can: C0 controls but tab, line feed and carriage return; the soft hyphen;
   zero-width characters and bidi marks, embeddings, overrides and isolates; the word joiner and the
   invisible operators; the BOM; the Unicode Tags block. A finding names the code point (`U+202E`), never
   the text around it, because the agent being protected reads the finding. Everything else a service
   screens for is the service's domain.

## 3. What differed between the three copies, and what kore chose

| | the copies | kore |
|---|---|---|
| A bare token without `Bearer` | accepted by two, refused by one | refused (rule 3) |
| A length mismatch | returned early in all three | no early exit (rule 3) |
| `Host` missing while hosts are configured | let through by two, refused by the SDK in the third | refused (rule 4) |
| IPv6 literal in `Host` | cut at the first `:` by two | `[::1]:8080` is `[::1]` (rule 4) |
| SDK host check with no hosts configured | on (localhost only) in one, off in two | off (rule 4) |
| The refused host in the `400` body | interpolated into a JSON string | JSON-encoded (rule 5) |
| `protocolVersion` | a request rewrite in one, a test and no fix in another, nothing in the third | McpJson on the route, and a tripwire (rule 6) |
| Invisible characters | two hand-written sets that had diverged, in two of the services | one set, the union and the bidi marks and the Tags block (rule 8) |

## 4. What it does not do

- **OAuth 2.1 from the MCP specification** (protected resource metadata, resource indicators). A static
  bearer token is a deliberate deviation for single-tenant self-hosted services, not compliance.
- **Per-tool or per-tenant authorisation.** One token opens every tool the service registered.
- **Sessions.** Stateless only: the SDK builds a `Server` per request and closes its session after it.
  A service that needs server-to-client notifications needs the stateful transport and is not served.
- **The services' screens.** Which phrases read as instructions, which fields are scalars, whether text is
  withheld per value or per crash, the two-phase gates, and the notes an agent is shown differ between a
  log store and a crash store on purpose, and stay there.
- **The application's `StatusPages`.** A `status(HttpStatusCode.Unauthorized)` handler there sees the
  guard's `401` too. It must not send a non-browser client to a login page either.

## 5. Code anchors

| Service | Code |
|---|---|
| kore-library | `kore-mcp/src/commonMain/kotlin/io/github/youndie/kore/mcp/KoreMcp.kt` — `KoreMcpConfig`, `installKoreMcp`, the route-scoped guard and the `McpJson` hook |
| kore-library | `kore-mcp/src/commonMain/kotlin/io/github/youndie/kore/mcp/KoreMcpAuth.kt` — the verdict as a pure function of two header values, the host grammar, the constant-time comparison |
| kore-library | `kore-mcp/src/commonMain/kotlin/io/github/youndie/kore/mcp/McpJsonBody.kt` — what the hook re-encodes |
| kore-library | `kore-mcp/src/commonMain/kotlin/io/github/youndie/kore/mcp/HiddenCharacters.kt` — the one shared screening rule |
| kore-library | `kore-mcp/src/commonTest/kotlin/io/github/youndie/kore/mcp/` — the scenarios below |

## 6. Scenarios (BDD)

All automated in common code and run on every target kore builds; the path scenarios over a real CIO
engine and raw sockets.

### Scenario: without a token there is no endpoint
* **Given:** `installKoreMcp` with a null or blank token
* **When:** `POST /mcp` with an initialize request
* **Then:** `404`, and the application has no ContentNegotiation — nothing ran
* **Automated:** `KoreMcpEndpointTest.no token means no endpoint and nothing installed`, `KoreMcpEndpointTest.a blank token is the same as none`

### Scenario: a missing token is a JSON 401 and not a login page
* **Given:** a configured token
* **When:** an initialize request without `Authorization`
* **Then:** `401`, `{"error":"unauthorized"}` as `application/json`, `WWW-Authenticate: Bearer`, no
  `Location`, and the transport never asked for a `Server`
* **Automated:** `KoreMcpEndpointTest.a missing token gets a JSON 401 and not a login page`

### Scenario: a wrong token, a bare token and a browser-contour header are refused
* **Given:** a configured token
* **When:** the request carries `Bearer wrong`, the token without a scheme, or `X-Auth-Request-User`
* **Then:** `401` each time, and the transport never asked for a `Server`
* **Automated:** `KoreMcpEndpointTest.a wrong token and a bare token and the browser contour's header are all refused`, `KoreMcpAuthTest.the scheme is required - a bare token is refused`

### Scenario: the right token reaches the transport and its tools
* **Given:** a configured token and a tool registered in the block
* **When:** initialize, `tools/list` and `tools/call` with `Bearer <token>`
* **Then:** `200`, the tool is listed and answers
* **Automated:** `KoreMcpEndpointTest.the right token is let through to the transport`, `KoreMcpEndpointTest.tools registered in the block are listed and called`

### Scenario: every request line that routes to the transport meets the guard
* **Given:** a real CIO engine with the endpoint at `/mcp`
* **When:** `//mcp`, `/%6Dcp`, `/%6dcp` and `///mcp` are sent, each with and without the token
* **Then:** with the token each is served — it routes to the transport — and without it each is `401`
  and the transport never asked for a `Server`
* **Automated:** `KoreMcpPathTest.every request line that routes to the transport meets the guard`

### Scenario: a guard keyed on the path string does not
* **Given:** the same transport behind an application interceptor that compares `request.path()` to `/mcp`
* **When:** the same four request lines are sent without a token
* **Then:** each is answered with an initialize result — the control that makes the scenario above mean
  something
* **Automated:** `KoreMcpPathTest.control - a guard comparing the path string lets the same lines through`

### Scenario: the guard covers every method the transport answers
* **Given:** a configured token
* **When:** `GET` and `DELETE` without it, and `GET` with it
* **Then:** `401`, `401`, and the transport's own `405`
* **Automated:** `KoreMcpEndpointTest.the guard covers every method the transport answers`

### Scenario: a foreign host is a 400 when hosts are configured
* **Given:** `allowedHosts = ["mcp.example.com"]`
* **When:** `Host: evil.example`, then `Host: mcp.example.com:443`
* **Then:** `400` with `{"error":"invalid host"}`, then `200`
* **Automated:** `KoreMcpEndpointTest.a foreign host is a JSON 400 when hosts are configured`, `KoreMcpAuthTest.with hosts configured a foreign or missing or malformed host is refused before the token`

### Scenario: the host is not checked when none are configured
* **Given:** no allowed hosts
* **When:** `Host: mcp.example.com`, which the SDK's default would refuse
* **Then:** `200`
* **Automated:** `KoreMcpEndpointTest.the host is not checked when none are configured`

### Scenario: protocolVersion is answered under an application Json that omits defaults
* **Given:** the application's own ContentNegotiation with kotlinx's default `Json`
* **When:** initialize asks for the SDK's latest version, or for one the SDK does not know
* **Then:** the result carries `protocolVersion: 2025-11-25`
* **Automated:** `ProtocolVersionTest.the latest version is answered under an application Json that omits defaults`, `ProtocolVersionTest.a version the SDK does not know is answered with the one it falls back to`

### Scenario: every answer is what McpJson writes, under either application Json
* **Given:** the application's own ContentNegotiation with a `Json` that omits defaults and writes
  `null`s (pretty-printed), or with Ktor's `json()` and no argument; then `installKoreMcp`
* **When:** initialize, a `ping` carrying a key the protocol does not define, `tools/list`,
  `tools/call`, an unknown method, a batch of two, and a body that does not parse
* **Then:** each body is byte for byte what `McpJson` writes for the message in it; the `ping` is a
  `200` under a `Json` that refuses its unknown key, so the request was not parsed by that `Json`
* **Automated:** `McpWireFormatTest.every answer is what McpJson writes under an application Json that omits defaults`, `McpWireFormatTest.every answer is what McpJson writes under json with no argument`, `McpWireFormatTest.the ping with an unknown key is one the application Json refuses`

### Scenario: the bare SDK still answers through the application's Json (tripwire)
* **Given:** the same two applications with the bare `mcpStatelessStreamableHttp` instead
* **When:** the same requests
* **Then:** answers differ from `McpJson` — on SDK 0.15.0 under the first `Json` the initialize result
  has no `protocolVersion` and the `ping` answer no `result`, under `json()` unset fields arrive as
  `null`. When either fails, the SDK no longer hands its answer to the application's
  ContentNegotiation, and the re-encoding of rule 6 is deleted
* **Automated:** `McpWireFormatTest.control - the bare SDK answers through an application Json that omits defaults`, `McpWireFormatTest.control - the bare SDK answers through json with no argument`

### Scenario: a hidden character is found and named without its text
* **Given:** text with a bidi override, a zero-width space, or an instruction in tag characters
* **When:** `HiddenCharacters.firstIn`
* **Then:** the first such code point, labelled `U+202E`; tab, newlines and an emoji with its variation
  selector pass
* **Automated:** `HiddenCharactersTest.every member of the set is found`, `HiddenCharactersTest.a whole instruction in tag characters is caught`, `HiddenCharactersTest.ordinary text and a stack trace and an emoji pass`

## 7. Quirks

- **Two measurements of `protocolVersion` disagreed and were both right.** One service saw the field
  vanish for the SDK's latest version and removed nothing; another saw it present for every version and
  removed its workaround. The variable was the application: one had installed ContentNegotiation with a
  `Json` that omits defaults, the other had none, so the SDK installed its own. Ktor's `json()` with no
  argument keeps the field — its `DefaultJson` encodes defaults — and is no safer: it writes an
  explicit `null` for every unset optional field, which `McpJson` never does.
- **The transport builds a `Server` per request.** Whatever the block registers is registered on every
  call, so it should register, not compute.
- **The SDK logs a warning when it finds the application's ContentNegotiation**, `ContentNegotiation is
  already installed. MCP requires json(McpJson)…`, on every start, without looking at the `Json` —
  Ktor's public API does not show it. Under `installKoreMcp` it names no harm. The transport reads
  requests raw and parses them with `McpJson`, and writes its refusals as finished text; the one thing
  it hands the application's ContentNegotiation is the message answering a POST, and the hook of rule 6
  re-encodes that one first. `McpWireFormatTest` holds both sides by the raw body.
