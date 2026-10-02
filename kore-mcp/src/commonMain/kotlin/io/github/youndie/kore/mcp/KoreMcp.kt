package io.github.youndie.kore.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The MCP endpoint's configuration (B-68): a token, the hosts it may be reached on, and its path.
 *
 * **A null or blank [token] means off**, not open. [installKoreMcp] then installs nothing at all — no
 * route, no guard, no ContentNegotiation — so there is nothing to reach even if the proxy in front is
 * misconfigured, and "forgot to set the secret" can never mean "exposed it".
 *
 * **Empty [allowedHosts] means the `Host` header is not checked.** Configure the host the endpoint is
 * published on and anything else is refused with `400`. The SDK's own check defaults to localhost only,
 * which is why the failure it guards against cannot be reproduced on a laptop and arrives with the first
 * deployment; kore turns it on only when there is a list to check against.
 */
public class KoreMcpConfig(
    token: String?,
    public val allowedHosts: List<String> = emptyList(),
    public val path: String = DEFAULT_PATH,
) {
    internal val token: String? = token?.takeIf { it.isNotBlank() }

    /** Whether [installKoreMcp] will install anything. */
    public val enabled: Boolean get() = token != null

    init {
        require(path.length > 1 && path.startsWith('/') && !path.endsWith('/')) {
            "kore-mcp: path must start with '/' and not end with one, got '$path'"
        }
    }

    // The token never reaches a log line through a config dump.
    override fun toString(): String = "KoreMcpConfig(enabled=$enabled, allowedHosts=$allowedHosts, path=$path)"

    public companion object {
        public const val DEFAULT_PATH: String = "/mcp"
    }
}

/**
 * Installs a stateless streamable-HTTP MCP endpoint at [KoreMcpConfig.path], guarded, and only when a
 * token is configured (B-68).
 *
 * [tools] runs on a fresh [Server] for every request, as the stateless transport asks; register tools,
 * prompts or resources on it. [capabilities] defaults to tools only, with `listChanged = false` — a
 * stateless endpoint has no stream to send a change notification on. Capabilities rather than the SDK's
 * `ServerOptions`, because those carry mutable fields and would be shared by every request's `Server`.
 *
 * What this does, and why each step is here rather than in the service:
 *
 * 1. **Nothing without a token** — see [KoreMcpConfig].
 * 2. **The guard sits on the route the transport answers, not in front of the application.** The SDK
 *    installs its own routing on the `Application`, so it cannot be nested in `authenticate {}`. An
 *    application interceptor would have to decide from the request path whether it applies, and routing
 *    does not resolve the raw path: it skips empty segments and URL-decodes each one, so `//mcp` and
 *    `/%6Dcp` reach the transport while a string comparison says they are somewhere else. Here the guard
 *    is a route-scoped plugin on the transport's own node, installed before the transport, so whatever
 *    routing resolves to it has been through the guard — `KoreMcpPathTest` holds that over a real socket,
 *    beside a string-keyed guard as its control. It does not reuse a browser contour's header-trusting
 *    provider: a proxy that authorises by `X-Auth-Request-*` accepts whatever identity is claimed.
 * 3. **The host allowlist** — [KoreMcpAuth]; the SDK's own `DnsRebindingProtection` stays on behind it
 *    when hosts are configured, with the same list and the same grammar, so the two cannot disagree.
 * 4. **A refusal is a JSON `401` or `400`, never a redirect.** An application's `StatusPages` handler for
 *    `401` still sees it — that one must not send a machine to a login page either.
 * 5. **MCP messages leave in MCP's JSON**, whatever ContentNegotiation the application installed — see
 *    [McpMessagesInMcpJson]. With an application `Json` that omits defaults, SDK 0.15.0 drops
 *    `protocolVersion` from the initialize result — no current client connects without it — and the
 *    whole `result` of a `ping`; under Ktor's `json()` every unset optional field arrives as `null`.
 *
 * **Install the application's own `ContentNegotiation` before this call**, or not at all. The SDK installs
 * one with its `McpJson` on the whole application when it finds none, and a later `install` then throws
 * `DuplicatePluginException`.
 */
public fun Application.installKoreMcp(
    config: KoreMcpConfig,
    serverInfo: Implementation,
    capabilities: ServerCapabilities = TOOLS_ONLY,
    tools: Server.() -> Unit,
) {
    val token = config.token ?: return
    val auth = KoreMcpAuth(token, config.allowedHosts)

    // BEFORE the transport, on the same node: `route(path)` returns the existing child for an equal
    // selector, so the SDK's `post`/`get`/`delete` below land under this plugin.
    routing {
        route(config.path) {
            install(KoreMcpGuard) { this.auth = auth }
        }
    }

    mcpStatelessStreamableHttp(
        path = config.path,
        enableDnsRebindingProtection = config.allowedHosts.isNotEmpty(),
        allowedHosts = config.allowedHosts.ifEmpty { null },
    ) {
        // The block is a factory called per request, not a receiver on one Server.
        Server(serverInfo = serverInfo, options = ServerOptions(capabilities = capabilities)).apply(tools)
    }
}

private val TOOLS_ONLY = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

private class KoreMcpGuardConfig {
    lateinit var auth: KoreMcpAuth
}

private val KoreMcpGuard =
    createRouteScopedPlugin("KoreMcpGuard", ::KoreMcpGuardConfig) {
        val auth = pluginConfig.auth

        onCall { call ->
            val verdict = auth.check(call.request.header(HttpHeaders.Host), call.request.header(HttpHeaders.Authorization))
            when (verdict) {
                KoreMcpVerdict.Allowed -> {
                    Unit
                }

                KoreMcpVerdict.Unauthorized -> {
                    // RFC 6750 §3: a 401 names the scheme it wants. A code, not a login page.
                    call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                    call.respondText(
                        buildJsonObject { put("error", "unauthorized") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized,
                    )
                }

                is KoreMcpVerdict.InvalidHost -> {
                    // The host is echoed for the operator who mistyped the list, and encoded rather
                    // than interpolated: it is whatever the caller sent.
                    call.respondText(
                        buildJsonObject {
                            put("error", "invalid host")
                            verdict.host?.let { put("host", it) }
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                }
            }
        }

        on(McpMessagesInMcpJson, Unit)
    }

/**
 * Serialises the SDK's JSON-RPC responses with its own `McpJson` before any ContentNegotiation sees them.
 *
 * WHY. SDK 0.15.0 answers a JSON-mode POST with `call.respond(payload)`, which goes through whatever
 * ContentNegotiation the application installed — the SDK installs its own only when there is none, and
 * otherwise logs a warning without looking at the `Json`. That answer is the one thing the transport
 * hands the application's ContentNegotiation: it reads requests raw and parses them with `McpJson`, and
 * writes its refusals as finished text. Whatever the application's `Json` does differently then reaches
 * the client. One with `encodeDefaults = false` (kotlinx's own default) drops
 * `InitializeResult.protocolVersion` exactly when the negotiated version equals its default — the SDK's
 * latest, which is also what any version it does not know falls back to — so clients fail schema
 * validation and never connect; it drops the whole `result` of a `ping` the same way. Ktor's `json()`
 * keeps both and writes an explicit `null` for every unset optional field.
 *
 * WHY HERE. In the send pipeline's `Before` phase of the transport's own route: earlier than the
 * `Transform` phase where ContentNegotiation serialises, and invisible to every other route of the
 * application. A JSON-RPC message becomes text the negotiation then leaves alone; anything else passes.
 *
 * REMOVE when the SDK no longer hands its answer to the application's ContentNegotiation — the day the
 * controls in `McpWireFormatTest` go red. Not when one field is fixed: a `protocolVersion` serialised
 * by the SDK would still leave `ping` and the `null`s to whatever `Json` the application chose.
 */
private object McpMessagesInMcpJson : Hook<Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: Unit,
    ) {
        pipeline.sendPipeline.intercept(ApplicationSendPipeline.Before) { subject ->
            mcpJsonBody(subject)?.let { proceedWith(it) }
        }
    }
}
