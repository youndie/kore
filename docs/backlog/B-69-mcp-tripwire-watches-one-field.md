---
id: B-69
title: "kore-mcp's tripwire watches one field, and would delete the re-encoding while answers still break"
status: done
priority: P2
size: XS
stage: m6-release
epic: feature-mcp-endpoint
blocked_by: []
---

# B-69 — kore-mcp's tripwire watches one field, and would delete the re-encoding while answers still break

[B-68](B-68-mcp-endpoint.md) re-encodes the stateless transport's answers with `McpJson` on the
endpoint's route, and its tripwire asserted that the bare SDK drops `protocolVersion` under an
application `Json` that omits defaults. Its KDoc, the feature document and the catalogue all said to
delete the re-encoding when that assertion failed. That is the wrong condition. The hook protects every
answer the SDK hands to the application's ContentNegotiation, and `protocolVersion` is one field of one
of them: the same `Json` drops the whole `result` of a `ping`, and Ktor's `json()` with no argument
writes an explicit `null` for every unset optional field. An SDK release that fixed `protocolVersion`
alone would have turned the tripwire red and taken the hook away from a consumer whose `Json` omits
defaults.

Found reading a consumer's start-up warning (`ContentNegotiation is already installed. MCP requires
json(McpJson)…`): its whole-body test
(`youndie/metrik@b006d39!/server/src/commonTest/kotlin/io/github/youndie/metrik/server/mcp/McpWireFormatTest.kt`)
compares every answer with `McpJson`, and the bare SDK under the same `Json` is its control. The feature
document's quirk about the same warning — "about the application's other routes" — was also wrong: the
SDK logs it without looking at the `Json`, and under `installKoreMcp` it names no harm.

- **`McpWireFormatTest` is the tripwire.** Initialize, a `ping`, `tools/list`, `tools/call`, a JSON-RPC
  error, a batch and a body that does not parse, under two application `Json`s — kotlinx's defaults
  (`encodeDefaults = false`, `explicitNulls = true`) pretty-printed, and Ktor's `json()` — each body
  compared byte for byte with what `McpJson` writes for the message in it. `prettyPrint` makes every
  answer that went through the application's `Json` differ, not only those with a default or a `null`
  in them.
- **The controls are the removal signal.** The same requests to the bare `mcpStatelessStreamableHttp`
  under each `Json` must differ somewhere; they go red only when no answer goes through the
  application's ContentNegotiation any more, and that is the condition the hook's KDoc now names.
- **`ProtocolVersionTest` keeps its three tests** of what a client gets, and loses the tripwire.
- No code changed in `kore-mcp`; KDoc, tests and documents only, so no release.

## What was read, not assumed

| Fact | Where verified |
|---|---|
| The SDK logs the warning whenever it finds ContentNegotiation, without looking at the `Json`, and installs its own only when there is none | `kotlin-sdk-server-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/server/KtorServerHelpers.kt:29-44` |
| A request is read raw and parsed with `McpJson` | `kotlin-sdk-server-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/server/StreamableHttpServerTransport.kt:841-857`, `.../RequestBody.kt:25-28` |
| Refusals are written as finished text; the one body through `call.respond` is the answer to a POST | `kotlin-sdk-server-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/server/StreamableHttpServerTransport.kt:941-950`, `.../StreamableHttpServerTransport.kt:563` |
| `ping` is answered with `EmptyResult()`, which is exactly the default of `JSONRPCResponse.result` — so a `Json` that omits defaults omits the whole `result` | `kotlin-sdk-core-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/shared/Protocol.kt:336-338`, `kotlin-sdk-core-0.15.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/types/jsonRpc.kt:213` |
| Ktor's `DefaultJson` encodes defaults and leaves `explicitNulls` at `true` | `ktor-serialization-kotlinx-json-3.6.0-sources.jar!/commonMain/io/ktor/serialization/kotlinx/json/JsonSupport.kt:25-33` |

## What was measured

- **Green as written**, `:kore-mcp:jvmTest` and `:kore-mcp:linuxX64Test`: `McpWireFormatTest` 5 of 5 and
  `ProtocolVersionTest` 3 of 3 on each, from the result files.
- **Mutation — the re-encoding removed from the hook**: both `McpWireFormatTest` tests through kore red
  on both targets, and the two `ProtocolVersionTest` tests that use an application `Json`; nothing else.
  The failures are the measurement of the harm on SDK 0.15.0. Under the omitting `Json` the initialize
  result has no `protocolVersion` — `McpJson` itself cannot tell which result it is — and the `ping`
  answer, alone and in the batch, is `{"id":…,"jsonrpc":"2.0"}`; the other three message answers
  (`tools/list`, `tools/call`, the error) differ only in whitespace. Under `json()` all six message answers carry `null`s (`"data":null` on the error,
  `"_meta":null` on the `ping` result). The body that does not parse matched under both: the SDK's
  refusal does not pass through ContentNegotiation.

## Done when

- [x] The tripwire compares whole answers under both `Json`s, with the bare SDK as its controls —
  `McpWireFormatTest`.
- [x] The hook's KDoc, the feature document (rule 6, scenarios, quirks), the catalogue and the upstream
  proposal name the right removal condition.
