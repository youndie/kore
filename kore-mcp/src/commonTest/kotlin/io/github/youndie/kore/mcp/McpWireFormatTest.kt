package io.github.youndie.kore.mcp

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * THE TRIPWIRE for `McpMessagesInMcpJson` (B-68, B-69): every answer the endpoint sends is, byte for
 * byte, what the SDK's `McpJson` writes for the message in it — whatever `Json` the application gave its
 * own ContentNegotiation.
 *
 * On SDK 0.15.0 the stateless transport touches the application's ContentNegotiation at exactly one
 * point. It reads a request raw (`receiveChannel`) and parses it with `McpJson`; it writes its refusals as
 * finished text (`reject`, `respondText`); and it answers a POST with `call.respond(payload)` — the one
 * thing the application's `Json` gets to encode. That is also why the SDK's start-up warning ("MCP
 * requires json(McpJson)…") names no harm under `installKoreMcp`: the hook re-encodes that one answer
 * before ContentNegotiation sees it.
 *
 * What the application's `Json` does to that answer without the hook, measured on 0.15.0 by the
 * controls below: one that omits defaults (kotlinx's own `encodeDefaults = false`) drops
 * `protocolVersion` from the initialize result and the whole `result` from a `ping` answer, which then
 * carries neither `result` nor `error` and is no longer JSON-RPC; Ktor's `json()` with no argument keeps
 * both and writes an explicit `null` for every unset optional field. So the measure is the whole body,
 * not a field: a test that watched `protocolVersion` alone would go red the day the SDK fixed that field,
 * and the hook would be deleted while the rest still broke.
 *
 * **When a control fails, the SDK no longer hands its answer to the application's ContentNegotiation.**
 * Then, and not before, delete `McpMessagesInMcpJson` and `mcpJsonBody`, both controls, and the request
 * test at the bottom; keep the two tests through kore.
 */
class McpWireFormatTest {
    /**
     * Requests the transport answers with a message, one of each shape it sends: the initialize result
     * for the SDK's latest version (the value `protocolVersion` defaults to), a `ping` (an empty result),
     * a tool list and a tool call (results with unset optional fields), a JSON-RPC error, a batch (the
     * list branch of `mcpJsonBody`), and a body that does not parse — refused by the SDK in finished text,
     * which every `Json` leaves alone.
     *
     * The `ping` carries a key the protocol does not define. [OMITS_DEFAULTS] refuses unknown keys, so its
     * `200` under that `Json` says the request was not parsed by the application's `Json` — the request
     * side of the claim above. The last test is its control.
     */
    private val exchanges =
        listOf(
            Exchange("initialize", initializeBody(LATEST_PROTOCOL_VERSION)),
            Exchange("ping with a key the protocol does not define", PING_WITH_UNKNOWN_KEY),
            Exchange("tools/list", """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""),
            Exchange(
                "tools/call",
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{"text":"a"}}}""",
            ),
            Exchange("a JSON-RPC error", """{"jsonrpc":"2.0","id":5,"method":"no/such/method"}"""),
            Exchange(
                "batch",
                """[{"jsonrpc":"2.0","id":6,"method":"ping"},{"jsonrpc":"2.0","id":7,"method":"tools/list"}]""",
            ),
            Exchange("a body that does not parse", """{"jsonrpc":"2.0","id":""", HttpStatusCode.BadRequest),
        )

    @Test
    fun `every answer is what McpJson writes under an application Json that omits defaults`() =
        testApplication {
            endpoint(OMITS_DEFAULTS, throughKore = true)

            assertEquals(emptyMap(), mismatches())
        }

    @Test
    fun `every answer is what McpJson writes under json with no argument`() =
        testApplication {
            endpoint(DefaultJson, throughKore = true)

            assertEquals(emptyMap(), mismatches())
        }

    /**
     * CONTROL for the first test, and the signal that the hook is still needed: the same tool, the same
     * requests, the same `Json`, the transport installed by the bare SDK — and its answers go out through
     * the application's `Json`. Without it, the green above could mean that the measure catches nothing.
     *
     * Red means the SDK has stopped answering through the application's ContentNegotiation — see the
     * class KDoc. `prettyPrint` in [OMITS_DEFAULTS] makes every answer that went that way differ, so this
     * goes red only when none did, and not when one field was fixed.
     */
    @Test
    fun `control - the bare SDK answers through an application Json that omits defaults`() =
        testApplication {
            endpoint(OMITS_DEFAULTS, throughKore = false)

            assertTrue(mismatches().isNotEmpty(), "the SDK no longer answers through ContentNegotiation - see the KDoc")
        }

    /** CONTROL for the second test: on 0.15.0 the bare SDK under `json()` writes the `null`s of unset fields. */
    @Test
    fun `control - the bare SDK answers through json with no argument`() =
        testApplication {
            endpoint(DefaultJson, throughKore = false)

            assertTrue(mismatches().isNotEmpty(), "the SDK no longer answers through ContentNegotiation - see the KDoc")
        }

    @Test
    fun `the ping with an unknown key is one the application Json refuses`() {
        // The `200` of that exchange under OMITS_DEFAULTS means "not parsed by it" only if it cannot be.
        assertFails { OMITS_DEFAULTS.decodeFromString(message, PING_WITH_UNKNOWN_KEY) }
    }

    /** The application's own ContentNegotiation on [json], then the endpoint — through kore or the bare SDK. */
    private fun ApplicationTestBuilder.endpoint(
        json: Json,
        throughKore: Boolean,
    ) = application {
        install(ContentNegotiation) { json(json) }
        if (throughKore) {
            installKoreMcp(KoreMcpConfig(TOKEN), INFO) { echoTool() }
        } else {
            mcpStatelessStreamableHttp(path = MCP, enableDnsRebindingProtection = false) {
                Server(INFO, ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))))
                    .apply { echoTool() }
            }
        }
    }

    /** Every exchange whose body is not what `McpJson` writes, with what it was instead. */
    private suspend fun ApplicationTestBuilder.mismatches(): Map<String, String> =
        exchanges
            .mapNotNull { exchange ->
                val response =
                    client.post(MCP) {
                        bearer(TOKEN)
                        header(HttpHeaders.Accept, ACCEPT)
                        contentType(ContentType.Application.Json)
                        setBody(exchange.request)
                    }
                val body = response.bodyAsText()
                assertEquals(exchange.status, response.status, "${exchange.name}: $body")
                mismatch(body)?.let { exchange.name to it }
            }.toMap()

    private fun Server.echoTool() {
        addTool(name = "echo", description = "answers with what it was given", inputSchema = ToolSchema()) { request ->
            CallToolResult(content = listOf(TextContent("echo:${request.arguments}")))
        }
    }

    private class Exchange(
        val name: String,
        val request: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
    )

    private companion object {
        val INFO = Implementation(name = "kore-mcp-test", version = "0")

        const val PING_WITH_UNKNOWN_KEY = """{"jsonrpc":"2.0","id":2,"method":"ping","x-unknown":true}"""

        /**
         * The application `Json` that broke a consumer: kotlinx's own defaults, spelled out — defaults
         * omitted, `null`s written, unknown keys refused — and pretty-printed, so an answer encoded by it
         * differs from `McpJson` in every case, not only where a default or a `null` happens to be.
         */
        val OMITS_DEFAULTS: Json =
            Json {
                encodeDefaults = false
                explicitNulls = true
                ignoreUnknownKeys = false
                prettyPrint = true
            }

        val message = serializer<JSONRPCMessage>()
        val batch = ListSerializer(message)

        /**
         * Null when [body] is exactly what `McpJson` writes for the message, or the batch, in it; otherwise
         * how it differs. Read and written by `McpJson`: a dropped field comes back as its default, an
         * extra `null` is dropped, whitespace is not written — so the bytes differ; and a body that lost
         * what identifies the message's kind does not parse at all.
         */
        fun mismatch(body: String): String? {
            val rewritten =
                runCatching {
                    if (body.startsWith("[")) {
                        McpJson.encodeToString(batch, McpJson.decodeFromString(batch, body))
                    } else {
                        McpJson.encodeToString(message, McpJson.decodeFromString(message, body))
                    }
                }.getOrElse { return "not a JSON-RPC message (${it.message}): $body" }
            return if (rewritten == body) null else "McpJson writes $rewritten, got $body"
        }
    }
}
