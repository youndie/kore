package io.github.youndie.kore.mcp

import io.ktor.http.HttpStatusCode
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.utils.io.readLine
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B-68: **the guard is wherever the transport is**, for every request line routing resolves to it.
 *
 * Over a real CIO engine and a raw socket, because the request line is the thing under test and an
 * HTTP client is free to tidy `//mcp` before it leaves. Ktor's router skips empty segments and decodes
 * each segment (`SegmentedPath`, `RoutingPathTree.tryResolve`, ktor-server-core 3.6.0), so these lines
 * all reach the transport's `post` — which the first assertion of each case proves, with the token,
 * before the second shows the guard refusing the same line without it.
 *
 * The control is the other place a guard can go: an application interceptor ahead of the transport that
 * decides by comparing `request.path()` to the path. It lets the same lines through untouched.
 */
class KoreMcpPathTest {
    private val info = Implementation(name = "kore-mcp-test", version = "0")

    /** Request lines that are not `/mcp` as a string and are `/mcp` to the router. */
    private val aliases = listOf("//mcp", "/%6Dcp", "/%6dcp", "///mcp")

    @Test
    fun `every request line that routes to the transport meets the guard`() =
        runBlocking {
            var built = 0
            serve({ installKoreMcp(KoreMcpConfig(TOKEN), info) { built++ } }) { port ->
                for (line in listOf(MCP) + aliases) {
                    // It routes: with the token this line is served by the transport.
                    assertEquals(200, post(port, line, token = TOKEN).status, "$line did not reach the transport at all")
                    val before = built
                    // And it is guarded: without the token the same line is refused before the transport.
                    val refused = post(port, line, token = null)
                    assertEquals(401, refused.status, "$line without a token: ${refused.body}")
                    assertEquals(before, built, "$line without a token reached the transport")
                }
            }
        }

    @Test
    fun `control - a guard comparing the path string lets the same lines through`() =
        runBlocking {
            serve({ pathStringGuard() }) { port ->
                assertEquals(401, post(port, MCP, token = null).status, "the control's guard does not guard /mcp itself")
                for (line in aliases) {
                    val response = post(port, line, token = null)
                    // An initialize answered without a token: the endpoint is open on this line.
                    assertEquals(200, response.status, "$line: ${response.body}")
                }
            }
        }

    /** An interceptor before the transport, keyed on the path string. */
    private fun Application.pathStringGuard() {
        intercept(ApplicationCallPipeline.Plugins) {
            if (context.request.path() != MCP) return@intercept
            if (context.request.headers["Authorization"] != "Bearer $TOKEN") {
                context.respondText("""{"error":"unauthorized"}""", status = HttpStatusCode.Unauthorized)
                finish()
            }
        }
        mcpStatelessStreamableHttp(path = MCP, enableDnsRebindingProtection = false) {
            Server(info, ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))))
        }
    }

    private suspend fun serve(
        module: Application.() -> Unit,
        block: suspend (port: Int) -> Unit,
    ) {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1", module = module)
        server.start(wait = false)
        try {
            block(server.engine.resolvedConnectors().first().port)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        }
    }

    private class Answer(
        val status: Int,
        val body: String,
    )

    /** One request on its own connection, written byte for byte: `Connection: close`, read to the end. */
    private suspend fun post(
        port: Int,
        target: String,
        token: String?,
    ): Answer =
        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().connect("127.0.0.1", port).use { socket ->
                val read = socket.openReadChannel()
                val write = socket.openWriteChannel(autoFlush = true)
                val body = initializeBody("2025-06-18")
                val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
                write.writeStringUtf8(
                    "POST $target HTTP/1.1\r\nHost: localhost\r\n$auth" +
                        "Accept: $ACCEPT\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.encodeToByteArray().size}\r\nConnection: close\r\n\r\n$body",
                )
                val statusLine = checkNotNull(read.readLine()) { "$target: the connection closed before a status line" }
                while (true) {
                    val header = checkNotNull(read.readLine()) { "$target: the connection closed inside the headers" }
                    if (header.isEmpty()) break
                }
                Answer(statusLine.split(' ')[1].toInt(), read.readRemaining().readString())
            }
        }
}
